package com.example.ragagent.eval;

import com.example.ragagent.rag.RagDocumentConstants;
import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import com.example.ragagent.rag.impl.ParentChildDocumentRetriever;
import com.example.ragagent.rag.impl.RedisBm25ChildChunkRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Profile("rag-eval")
public class DuReaderRecallEvaluationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DuReaderRecallEvaluationRunner.class);

    private static final int RRF_K = 60;
    private static final int MIN_CHILD_RESULTS_TO_KEEP = 4;
    private static final int MAX_CHILD_RESULTS_TO_CONSIDER = 10;

    private final ParentChildDocumentIndexer documentIndexer;
    private final ParentChildDocumentRetriever denseRetriever;
    private final RedisBm25ChildChunkRetriever bm25Retriever;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext applicationContext;
    private final String inputFile;
    private final int maxExamples;
    private final boolean indexDocuments;
    private final List<Integer> recallKs;
    private final int missExamplesToLog;
    private final Map<String, List<Document>> denseChildDocumentsCache = new LinkedHashMap<>();
    private final Map<String, List<Document>> bm25ChildDocumentsCache = new LinkedHashMap<>();

    public DuReaderRecallEvaluationRunner(ParentChildDocumentIndexer documentIndexer,
                                          ParentChildDocumentRetriever denseRetriever,
                                          RedisBm25ChildChunkRetriever bm25Retriever,
                                          ObjectMapper objectMapper,
                                          ConfigurableApplicationContext applicationContext,
                                          @Value("${app.rag-eval.input-file}") String inputFile,
                                          @Value("${app.rag-eval.max-examples:100}") int maxExamples,
                                          @Value("${app.rag-eval.index-documents:true}") boolean indexDocuments,
                                          @Value("${app.rag-eval.ks:1,3}") String recallKs,
                                          @Value("${app.rag-eval.miss-examples-to-log:5}") int missExamplesToLog) {
        this.documentIndexer = documentIndexer;
        this.denseRetriever = denseRetriever;
        this.bm25Retriever = bm25Retriever;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.inputFile = inputFile;
        this.maxExamples = maxExamples;
        this.indexDocuments = indexDocuments;
        this.recallKs = parseRecallKs(recallKs);
        this.missExamplesToLog = Math.max(0, missExamplesToLog);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            List<EvalSample> samples = loadSamples(Path.of(inputFile), maxExamples);
            log.info("Loaded {} DuReader eval samples from {}", samples.size(), inputFile);

            if (samples.isEmpty()) {
                log.warn("No valid DuReader samples found, skip evaluation");
                return;
            }

            int actualIndexedDocumentCount = 0;
            if (indexDocuments) {
                actualIndexedDocumentCount = indexSamples(samples);
            }
            else {
                log.warn("app.rag-eval.index-documents=false，本次不会导入 DuReader 文档；评测将复用 Redis 中已有的索引数据。");
            }

            List<RetrievalStrategy> strategies = List.of(
                    new RetrievalStrategy("dense-only", this::retrieveDenseOnly, false),
                    new RetrievalStrategy("bm25-only", this::retrieveBm25Only, false),
                    new RetrievalStrategy("hybrid-rrf", this::retrieveHybridRrfWithoutTruncation, false),
                    new RetrievalStrategy("hybrid-rrf-dynamic-truncation", this::retrieveHybridRrfWithDynamicTruncation, true)
            );

            List<EvaluationResult> results = new ArrayList<>();
            for (RetrievalStrategy strategy : strategies) {
                log.info("Start DuReader ablation evaluation: {}", strategy.name());
                results.add(evaluate(samples, actualIndexedDocumentCount, strategy));
            }

            log.info("""

                    DuReader Recall@K 消融实验结果：
                    inputFile={}
                    samples={}
                    candidateDocuments={}
                    actualIndexedDocuments={}
                    {}
                    """,
                    inputFile,
                    samples.size(),
                    samples.stream().mapToInt(sample -> sample.documents().size()).sum(),
                    actualIndexedDocumentCount,
                    renderAblationSummary(results));
        }
        finally {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }

    private List<EvalSample> loadSamples(Path path, int limit) throws Exception {
        List<EvalSample> samples = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && samples.size() < limit) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }

                JsonNode root = objectMapper.readTree(line);
                EvalSample sample = toEvalSample(root);
                if (sample != null) {
                    samples.add(sample);
                }
            }
        }
        return List.copyOf(samples);
    }

    private EvalSample toEvalSample(JsonNode root) {
        String question = root.path("question").asText("");
        String questionId = root.path("question_id").asText("");
        JsonNode documentsNode = root.path("documents");
        if (!StringUtils.hasText(question) || !StringUtils.hasText(questionId) || !documentsNode.isArray()) {
            return null;
        }

        List<EvalDocument> documents = readDocuments(questionId, documentsNode);
        Set<Integer> goldIndexes = readGoldDocumentIndexes(root, documentsNode.size());
        if (documents.isEmpty() || goldIndexes.isEmpty()) {
            return null;
        }

        Set<String> goldSourceIds = new LinkedHashSet<>();
        for (EvalDocument document : documents) {
            if (goldIndexes.contains(document.documentIndex())) {
                goldSourceIds.add(document.sourceId());
            }
        }

        if (goldSourceIds.isEmpty()) {
            return null;
        }
        return new EvalSample(questionId, question, documents, Set.copyOf(goldSourceIds));
    }

    private List<EvalDocument> readDocuments(String questionId, JsonNode documentsNode) {
        List<EvalDocument> documents = new ArrayList<>();
        for (int index = 0; index < documentsNode.size(); index++) {
            JsonNode documentNode = documentsNode.get(index);
            String title = documentNode.path("title").asText("");
            List<String> paragraphs = readStringArray(documentNode.path("paragraphs"));
            String text = buildDocumentText(title, paragraphs);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            documents.add(new EvalDocument(index, sourceIdFor(questionId, index), title, text));
        }
        return List.copyOf(documents);
    }

    private Set<Integer> readGoldDocumentIndexes(JsonNode root, int documentCount) {
        Set<Integer> goldIndexes = new LinkedHashSet<>();
        JsonNode answerDocs = root.path("answer_docs");
        if (answerDocs.isArray()) {
            for (JsonNode answerDoc : answerDocs) {
                int index = answerDoc.asInt(-1);
                if (index >= 0 && index < documentCount) {
                    goldIndexes.add(index);
                }
            }
        }

        if (!goldIndexes.isEmpty()) {
            return goldIndexes;
        }

        JsonNode documents = root.path("documents");
        if (documents.isArray()) {
            for (int index = 0; index < documents.size(); index++) {
                if (documents.get(index).path("is_selected").asBoolean(false)) {
                    goldIndexes.add(index);
                }
            }
        }
        return goldIndexes;
    }

    private List<String> readStringArray(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            String value = item.asText("");
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }

    private String buildDocumentText(String title, List<String> paragraphs) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(title)) {
            builder.append(title.trim());
        }
        for (String paragraph : paragraphs) {
            if (!StringUtils.hasText(paragraph)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(paragraph.trim());
        }
        return builder.toString();
    }

    private int indexSamples(List<EvalSample> samples) {
        int indexedDocumentCount = 0;
        for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
            EvalSample sample = samples.get(sampleIndex);
            for (EvalDocument document : sample.documents()) {
                documentIndexer.indexDocument(document.sourceId(), document.title(), document.text());
                indexedDocumentCount++;
            }
            if ((sampleIndex + 1) % 10 == 0) {
                log.info("Indexed DuReader eval samples: {}/{}", sampleIndex + 1, samples.size());
            }
        }
        log.info("Indexed {} DuReader candidate documents", indexedDocumentCount);
        return indexedDocumentCount;
    }

    private EvaluationResult evaluate(List<EvalSample> samples,
                                      int actualIndexedDocumentCount,
                                      RetrievalStrategy strategy) {
        Map<Integer, Integer> hitCounts = new LinkedHashMap<>();
        for (Integer k : recallKs) {
            hitCounts.put(k, 0);
        }

        long totalLatencyNanos = 0L;
        int totalRetrievedParents = 0;
        int loggedMissCount = 0;
        for (int index = 0; index < samples.size(); index++) {
            EvalSample sample = samples.get(index);
            long startedAt = System.nanoTime();
            List<Document> retrievedDocuments = strategy.retrieve(sample.question());
            totalLatencyNanos += System.nanoTime() - startedAt;
            totalRetrievedParents += retrievedDocuments.size();

            List<RetrievedSource> retrievedSources = deduplicateBySourceId(retrievedDocuments);
            List<String> retrievedSourceIds = retrievedSources.stream()
                    .map(RetrievedSource::sourceId)
                    .toList();

            for (Integer k : recallKs) {
                if (isHit(retrievedSourceIds, sample.goldSourceIds(), k)) {
                    hitCounts.computeIfPresent(k, (ignored, count) -> count + 1);
                }
            }

            int largestK = recallKs.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (strategy.logMissExamples()
                    && !isHit(retrievedSourceIds, sample.goldSourceIds(), largestK)
                    && loggedMissCount < missExamplesToLog) {
                loggedMissCount++;
                log.info("""
                        DuReader miss sample:
                        strategy={}
                        questionId={}
                        question={}
                        goldSourceIds={}
                        retrievedSourceIds={}
                        retrievedTitles={}
                        """,
                        strategy.name(),
                        sample.questionId(),
                        sample.question(),
                        sample.goldSourceIds(),
                        retrievedSourceIds,
                        retrievedSources.stream().map(RetrievedSource::title).toList());
            }

            if ((index + 1) % 20 == 0) {
                log.info("Evaluated DuReader samples: {}/{} ({})", index + 1, samples.size(), strategy.name());
            }
        }

        return new EvaluationResult(
                strategy.name(),
                samples.size(),
                samples.stream().mapToInt(sample -> sample.documents().size()).sum(),
                actualIndexedDocumentCount,
                hitCounts,
                totalRetrievedParents,
                totalLatencyNanos
        );
    }

    private List<Document> retrieveDenseOnly(String question) {
        List<Document> childDocuments = retrieveDenseChildDocumentsWithCache(question);
        if (childDocuments.isEmpty()) {
            return List.of();
        }
        return denseRetriever.loadParentDocuments(childDocuments);
    }

    private List<Document> retrieveBm25Only(String question) {
        List<Document> childDocuments = retrieveBm25ChildDocumentsWithCache(question);
        if (childDocuments.isEmpty()) {
            return List.of();
        }
        return denseRetriever.loadParentDocuments(childDocuments);
    }

    private List<Document> retrieveHybridRrfWithoutTruncation(String question) {
        String normalizedQuestion = normalizeText(question);
        if (!StringUtils.hasText(normalizedQuestion)) {
            return List.of();
        }

        List<Document> denseChildDocuments = retrieveDenseChildDocumentsWithCache(normalizedQuestion);
        List<Document> bm25ChildDocuments = retrieveBm25ChildDocumentsWithCache(normalizedQuestion);
        List<Document> fusedChildDocuments = rankWithReciprocalRankFusion(denseChildDocuments, bm25ChildDocuments)
                .stream()
                .map(RankedChildDocument::document)
                .toList();
        if (fusedChildDocuments.isEmpty()) {
            return List.of();
        }
        return denseRetriever.loadParentDocuments(fusedChildDocuments);
    }

    private List<Document> retrieveHybridRrfWithDynamicTruncation(String question) {
        String normalizedQuestion = normalizeText(question);
        if (!StringUtils.hasText(normalizedQuestion)) {
            return List.of();
        }

        List<Document> denseChildDocuments = retrieveDenseChildDocumentsWithCache(normalizedQuestion);
        List<Document> bm25ChildDocuments = retrieveBm25ChildDocumentsWithCache(normalizedQuestion);
        List<Document> fusedChildDocuments = truncateByLargestNormalizedGap(
                rankWithReciprocalRankFusion(denseChildDocuments, bm25ChildDocuments)
        ).stream()
                .map(RankedChildDocument::document)
                .toList();
        if (fusedChildDocuments.isEmpty()) {
            return List.of();
        }
        return denseRetriever.loadParentDocuments(fusedChildDocuments);
    }

    private List<Document> retrieveDenseChildDocumentsWithCache(String question) {
        String normalizedQuestion = normalizeText(question);
        if (!StringUtils.hasText(normalizedQuestion)) {
            return List.of();
        }
        return denseChildDocumentsCache.computeIfAbsent(normalizedQuestion,
                key -> List.copyOf(denseRetriever.retrieveChildDocuments(key)));
    }

    private List<Document> retrieveBm25ChildDocumentsWithCache(String question) {
        String normalizedQuestion = normalizeText(question);
        if (!StringUtils.hasText(normalizedQuestion)) {
            return List.of();
        }
        return bm25ChildDocumentsCache.computeIfAbsent(normalizedQuestion,
                key -> List.copyOf(retrieveBm25ChildDocuments(key)));
    }

    private List<Document> retrieveBm25ChildDocuments(String question) {
        try {
            List<Document> documents = bm25Retriever.retrieve(question);
            return documents == null ? List.of() : documents;
        }
        catch (RuntimeException ex) {
            log.warn("BM25 child chunk retrieval failed during evaluation", ex);
            return List.of();
        }
    }

    private List<RankedChildDocument> rankWithReciprocalRankFusion(List<Document> denseDocuments,
                                                                   List<Document> bm25Documents) {
        Map<String, RankedChildDocument> rankedDocuments = new LinkedHashMap<>();
        applyRrfScores(rankedDocuments, denseDocuments);
        applyRrfScores(rankedDocuments, bm25Documents);

        return rankedDocuments.values().stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
    }

    private void applyRrfScores(Map<String, RankedChildDocument> rankedDocuments, List<Document> documents) {
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            String documentId = document == null ? "" : document.getId();
            if (!StringUtils.hasText(documentId)) {
                continue;
            }

            double incrementalScore = 1.0d / (RRF_K + index + 1);
            RankedChildDocument existing = rankedDocuments.get(documentId);
            if (existing == null) {
                rankedDocuments.put(documentId, new RankedChildDocument(document, incrementalScore));
                continue;
            }

            rankedDocuments.put(documentId, new RankedChildDocument(
                    mergeDocument(existing.document(), document),
                    existing.score() + incrementalScore
            ));
        }
    }

    private Document mergeDocument(Document existing, Document candidate) {
        if (existing == null) {
            return candidate;
        }
        if (candidate == null) {
            return existing;
        }
        if (StringUtils.hasText(existing.getText()) && existing.getMetadata() != null && !existing.getMetadata().isEmpty()) {
            return existing;
        }
        return candidate;
    }

    private List<RankedChildDocument> truncateByLargestNormalizedGap(List<RankedChildDocument> rankedDocuments) {
        if (rankedDocuments.isEmpty()) {
            return List.of();
        }
        if (rankedDocuments.size() <= MIN_CHILD_RESULTS_TO_KEEP) {
            return List.copyOf(rankedDocuments);
        }

        int upperBoundExclusive = Math.min(MAX_CHILD_RESULTS_TO_CONSIDER, rankedDocuments.size());
        double topScore = Math.max(rankedDocuments.get(0).score(), 1.0e-9d);
        double largestGap = Double.NEGATIVE_INFINITY;
        int cutIndexExclusive = upperBoundExclusive;

        for (int index = MIN_CHILD_RESULTS_TO_KEEP - 1; index < upperBoundExclusive - 1; index++) {
            double currentScore = rankedDocuments.get(index).score();
            double nextScore = rankedDocuments.get(index + 1).score();
            double normalizedGap = (currentScore - nextScore) / topScore;
            if (normalizedGap > largestGap) {
                largestGap = normalizedGap;
                cutIndexExclusive = index + 1;
            }
        }

        return List.copyOf(rankedDocuments.subList(0, cutIndexExclusive));
    }

    private String renderAblationSummary(List<EvaluationResult> results) {
        List<String> renderedResults = new ArrayList<>();
        for (EvaluationResult result : results) {
            renderedResults.add("""
                    strategy=%s
                      avgRetrievedParents=%s
                      avgLatencyMs=%s
                      metrics=%s""".formatted(
                    result.strategyName(),
                    String.format("%.2f", result.averageRetrievedParents()),
                    String.format("%.2f", result.averageLatencyMillis()),
                    result.renderMetrics()));
        }
        return String.join(System.lineSeparator(), renderedResults);
    }

    private boolean isHit(List<String> retrievedSourceIds, Set<String> goldSourceIds, int k) {
        int upperBound = Math.min(k, retrievedSourceIds.size());
        for (int index = 0; index < upperBound; index++) {
            if (goldSourceIds.contains(retrievedSourceIds.get(index))) {
                return true;
            }
        }
        return false;
    }

    private List<RetrievedSource> deduplicateBySourceId(List<Document> documents) {
        Map<String, RetrievedSource> deduplicatedSources = new LinkedHashMap<>();
        for (Document document : documents) {
            String sourceId = readSourceId(document);
            if (!StringUtils.hasText(sourceId) || deduplicatedSources.containsKey(sourceId)) {
                continue;
            }
            deduplicatedSources.put(sourceId, new RetrievedSource(sourceId, readTitle(document)));
        }
        return List.copyOf(deduplicatedSources.values());
    }

    private String readSourceId(Document document) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(RagDocumentConstants.METADATA_SOURCE_ID);
        return value == null ? "" : value.toString();
    }

    private String readTitle(Document document) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(RagDocumentConstants.METADATA_TITLE);
        return value == null ? "" : value.toString();
    }

    private String sourceIdFor(String questionId, int documentIndex) {
        return "dureader-" + questionId + "-doc-" + documentIndex;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<Integer> parseRecallKs(String value) {
        String[] parts = StringUtils.commaDelimitedListToStringArray(value);
        List<Integer> parsed = new ArrayList<>();
        for (String part : parts) {
            try {
                int k = Integer.parseInt(part.trim());
                if (k > 0) {
                    parsed.add(k);
                }
            }
            catch (NumberFormatException ignored) {
                // Ignore malformed K values and keep evaluating valid ones.
            }
        }
        return parsed.isEmpty() ? List.of(1, 3) : List.copyOf(parsed);
    }

    private record EvalSample(
            String questionId,
            String question,
            List<EvalDocument> documents,
            Set<String> goldSourceIds
    ) {
    }

    private record EvalDocument(
            int documentIndex,
            String sourceId,
            String title,
            String text
    ) {
    }

    private record RetrievedSource(
            String sourceId,
            String title
    ) {
    }

    @FunctionalInterface
    private interface RetrieverFunction {

        List<Document> retrieve(String question);
    }

    private record RetrievalStrategy(
            String name,
            RetrieverFunction retrieverFunction,
            boolean logMissExamples
    ) {

        private List<Document> retrieve(String question) {
            return retrieverFunction.retrieve(question);
        }
    }

    private record RankedChildDocument(
            Document document,
            double score
    ) {
    }

    private record EvaluationResult(
            String strategyName,
            int sampleCount,
            int candidateDocumentCount,
            int actualIndexedDocumentCount,
            Map<Integer, Integer> hitCounts,
            int totalRetrievedParents,
            long totalLatencyNanos
    ) {

        private double averageRetrievedParents() {
            return sampleCount == 0 ? 0.0d : (double) totalRetrievedParents / sampleCount;
        }

        private double averageLatencyMillis() {
            return sampleCount == 0 ? 0.0d : (double) totalLatencyNanos / sampleCount / 1_000_000.0d;
        }

        private String renderMetrics() {
            List<String> rendered = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : hitCounts.entrySet()) {
                double recall = sampleCount == 0 ? 0.0d : (double) entry.getValue() / sampleCount;
                rendered.add("Recall@" + entry.getKey() + "=" + String.format("%.4f", recall)
                        + " (" + entry.getValue() + "/" + sampleCount + ")");
            }
            return String.join(", ", rendered);
        }
    }
}
