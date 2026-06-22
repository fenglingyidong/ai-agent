package com.example.ragagent.rag.impl;

import com.example.ragagent.config.RagRetrievalConfiguration;
import com.example.ragagent.config.RagRetrievalProperties;
import com.example.ragagent.observability.RagTracing;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 混合父子分块检索器，融合 dense 向量召回和 Milvus BM25 sparse 召回。
 */
@Component
@Primary
public class ParentChildHybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(ParentChildHybridDocumentRetriever.class);
    private static final Pattern CHINESE_OR_DIGIT_WHITESPACE_PATTERN =
            Pattern.compile("(?<=[\\p{IsHan}\\d])\\s+(?=[\\p{IsHan}\\d])");
    private static final Pattern MULTIPLE_WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern COMPACT_KEYBOARD_PATTERN = Pattern.compile("(\\d+)\\s*键\\s*机械键盘");
    private static final Pattern KEYBOARD_ATTRIBUTE_PRICE_QUESTION_PATTERN =
            Pattern.compile(".*?(\\d+键\\s*机械键盘).*?(?:什么轴|轴).*?(?:多少钱|价格).*");

    private final ParentChildDocumentRetriever denseRetriever;
    private final MilvusBm25ChildChunkRetriever bm25Retriever;
    private final RagRetrievalProperties properties;
    private final Executor retrievalExecutor;
    private final RagTracing tracing;

    /**
     * 创建混合检索器，注入 dense/BM25 检索器、并行执行器和 tracing 组件。
     */
    public ParentChildHybridDocumentRetriever(ParentChildDocumentRetriever denseRetriever,
                                              MilvusBm25ChildChunkRetriever bm25Retriever,
                                              RagRetrievalProperties properties,
                                              @Qualifier(RagRetrievalConfiguration.RAG_RETRIEVAL_EXECUTOR)
                                              Executor retrievalExecutor,
                                              RagTracing tracing) {
        this.denseRetriever = denseRetriever;
        this.bm25Retriever = bm25Retriever;
        this.properties = properties;
        this.retrievalExecutor = retrievalExecutor;
        this.tracing = tracing;
    }

    /**
     * 对外仍暴露 Spring AI DocumentRetriever，内部先做双路召回，再回查父分块。
     */
    @Override
    public List<Document> retrieve(Query query) {
        String originalQuery = normalizeText(query == null ? null : query.text());
        if (!StringUtils.hasText(originalQuery)) {
            return List.of();
        }
        String retrievalQuery = rewriteRetrievalQuery(originalQuery);

        return tracing.inSpan("rag.hybrid.retrieve", () -> retrieveHybrid(originalQuery, retrievalQuery));
    }

    private List<Document> retrieveHybrid(String originalQuery, String retrievalQuery) {
        Span span = tracing.currentSpan();
        tracing.setAttribute(span, "rag.query.length", retrievalQuery.length());
        tracing.setAttribute(span, "rag.query.original.length", originalQuery.length());
        tracing.setAttribute(span, "rag.query.rewritten.length", retrievalQuery.length());
        tracing.setAttribute(span, "rag.query.rewritten.changed", !originalQuery.equals(retrievalQuery));
        tracing.captureRagContent(span, "rag.query.original", originalQuery);
        tracing.captureRagContent(span, "rag.query.rewritten", retrievalQuery);
        tracing.setAttribute(span, "rag.dense.top_k", properties.getDenseChildTopK());
        tracing.setAttribute(span, "rag.bm25.top_k", properties.getBm25ChildTopK());
        tracing.setAttribute(span, "rag.rrf.k", properties.getRrfK());
        tracing.setAttribute(span, "rag.max_parent_results", properties.getMaxParentResults());

        CompletableFuture<List<Document>> bm25Future = retrieveBm25ChildDocumentsAsync(retrievalQuery);

        List<Document> denseChildDocuments = retrieveDenseChildDocuments(retrievalQuery);
        List<Document> bm25ChildDocuments = bm25Future.join();
        List<RankedChildDocument> rankedChildDocuments =
                rankWithReciprocalRankFusion(denseChildDocuments, bm25ChildDocuments);
        List<Document> fusedChildDocuments = truncateByLargestNormalizedGap(rankedChildDocuments).stream()
                .map(RankedChildDocument::document)
                .toList();
        if (fusedChildDocuments.isEmpty()) {
            tracing.setAttribute(span, "rag.result.parent_count", 0);
            return List.of();
        }

        List<Document> parentDocuments = loadParentDocuments(fusedChildDocuments);
        tracing.setAttribute(span, "rag.result.parent_count", parentDocuments.size());
        return parentDocuments;
    }

    private List<Document> retrieveDenseChildDocuments(String normalizedQuery) {
        return tracing.inSpan("rag.dense.retrieve", () -> {
            Span span = tracing.currentSpan();
            tracing.setAttribute(span, "rag.dense.similarity_threshold", properties.getDenseSimilarityThreshold());
            tracing.setAttribute(span, "rag.dense.fallback_top_k", properties.getDenseFallbackTopK());
            try {
                List<Document> documents = safeDocuments(denseRetriever.retrieveChildDocuments(normalizedQuery));
                tracing.setAttribute(span, "rag.result.child_count", documents.size());
                tracing.setAttribute(span, "rag.status", "ok");
                return documents;
            }
            catch (RuntimeException ex) {
                tracing.setAttribute(span, "rag.status", "error");
                throw ex;
            }
        });
    }

    /**
     * BM25 路出现异常时降级为只使用 dense 结果，避免影响主流程。
     */
    private List<Document> retrieveBm25ChildDocuments(String query, Span span, AtomicBoolean outcomeRecorded) {
        try {
            List<Document> documents = bm25Retriever.retrieve(query);
            List<Document> safeDocuments = documents == null ? List.of() : documents;
            if (outcomeRecorded.compareAndSet(false, true)) {
                tracing.setAttribute(span, "rag.result.child_count", safeDocuments.size());
                tracing.setAttribute(span, "rag.bm25.degraded", false);
                tracing.setAttribute(span, "rag.status", "ok");
            }
            return safeDocuments;
        }
        catch (RuntimeException ex) {
            log.warn("BM25 child chunk retrieval failed, falling back to dense-only retrieval", ex);
            recordBm25Outcome(span, "error", ex, outcomeRecorded);
            return List.of();
        }
    }

    private CompletableFuture<List<Document>> retrieveBm25ChildDocumentsAsync(String query) {
        AtomicBoolean outcomeRecorded = new AtomicBoolean(false);
        AtomicBoolean spanEnded = new AtomicBoolean(false);
        Span bm25Span = tracing.startSpan("rag.bm25.retrieve");
        tracing.setAttribute(bm25Span, "rag.bm25.future_timeout_ms", properties.getBm25FutureTimeoutMs());
        tracing.setAttribute(bm25Span, "rag.bm25.rpc_deadline_ms", properties.getBm25RpcDeadlineMs());
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(properties.getBm25FutureTimeoutMs());
        try {
            return CompletableFuture
                    .<List<Document>>supplyAsync(() -> {
                        try (var ignored = tracing.makeCurrent(bm25Span)) {
                            if (System.nanoTime() >= deadlineNanos) {
                                recordBm25Outcome(bm25Span, "timeout", null, outcomeRecorded);
                                return List.of();
                            }
                            return retrieveBm25ChildDocuments(query, bm25Span, outcomeRecorded);
                        }
                    }, retrievalExecutor)
                    .orTimeout(properties.getBm25FutureTimeoutMs(), TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        try {
                            Throwable cause = unwrapCompletionException(ex);
                            String status = cause instanceof TimeoutException ? "timeout" : "error";
                            if ("error".equals(status)) {
                                log.warn("BM25 child chunk retrieval failed asynchronously, falling back to dense-only retrieval", cause);
                            }
                            recordBm25Outcome(bm25Span, status, cause, outcomeRecorded);
                            return List.of();
                        }
                        finally {
                            endBm25Span(bm25Span, spanEnded);
                        }
                    })
                    .whenComplete((documents, ex) -> {
                        if (ex == null) {
                            endBm25Span(bm25Span, spanEnded);
                        }
                    });
        }
        catch (RuntimeException ex) {
            log.warn("BM25 child chunk retrieval task rejected, falling back to dense-only retrieval", ex);
            recordBm25Outcome(bm25Span, "error", ex, outcomeRecorded);
            endBm25Span(bm25Span, spanEnded);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private void recordBm25Outcome(Span span, String status, Throwable ex, AtomicBoolean outcomeRecorded) {
        if (!outcomeRecorded.compareAndSet(false, true)) {
            return;
        }
        tracing.setAttribute(span, "rag.result.child_count", 0);
        tracing.setAttribute(span, "rag.bm25.degraded", true);
        tracing.setAttribute(span, "rag.status", status);
        if (ex != null && !"timeout".equals(status)) {
            tracing.recordError(span, ex);
        }
    }

    private void endBm25Span(Span span, AtomicBoolean spanEnded) {
        if (spanEnded.compareAndSet(false, true)) {
            tracing.endSpan(span);
        }
    }

    private Throwable unwrapCompletionException(Throwable ex) {
        if (ex instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return ex;
    }

    private List<Document> safeDocuments(List<Document> documents) {
        return documents == null ? List.of() : documents;
    }

    /**
     * 使用 RRF 融合两路 child 排名，避免直接比较不同量纲的分数。
     */
    private List<RankedChildDocument> rankWithReciprocalRankFusion(List<Document> denseDocuments,
                                                                   List<Document> bm25Documents) {
        return tracing.inSpan("rag.rrf.rank", () -> {
            Span span = tracing.currentSpan();
            tracing.setAttribute(span, "rag.input.dense_child_count", safeDocuments(denseDocuments).size());
            tracing.setAttribute(span, "rag.input.bm25_child_count", safeDocuments(bm25Documents).size());

            Map<String, RankedChildDocument> rankedDocuments = new LinkedHashMap<>();
            applyRrfScores(rankedDocuments, safeDocuments(denseDocuments));
            applyRrfScores(rankedDocuments, safeDocuments(bm25Documents));

            List<RankedChildDocument> ranked = rankedDocuments.values().stream()
                    .sorted((left, right) -> Double.compare(right.score(), left.score()))
                    .toList();
            tracing.setAttribute(span, "rag.result.fused_child_count", ranked.size());
            tracing.setAttribute(span, "rag.result.top_child_ids", String.join(",", tracing.topDocumentIds(
                    ranked.stream().map(RankedChildDocument::document).toList(),
                    10
            )));
            return ranked;
        });
    }

    /**
     * 将单路结果按排名累加到 RRF 分数中。
     */
    private void applyRrfScores(Map<String, RankedChildDocument> rankedDocuments, List<Document> documents) {
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            String documentId = document == null ? "" : document.getId();
            if (!StringUtils.hasText(documentId)) {
                continue;
            }

            double incrementalScore = 1.0d / (properties.getRrfK() + index + 1);
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

    /**
     * 复用已有文档对象，缺少文本或元数据时再用另一条召回结果补齐。
     */
    private List<RankedChildDocument> truncateByLargestNormalizedGap(List<RankedChildDocument> rankedDocuments) {
        return tracing.inSpan("rag.dynamic_truncate", () -> {
            Span span = tracing.currentSpan();
            tracing.setAttribute(span, "rag.input.fused_child_count", rankedDocuments == null ? 0 : rankedDocuments.size());
            tracing.setAttribute(span, "rag.truncate.min_child_results_to_keep", properties.getMinChildResultsToKeep());
            tracing.setAttribute(span, "rag.truncate.max_child_results_to_consider", properties.getMaxChildResultsToConsider());
            tracing.setAttribute(span, "rag.truncate.min_normalized_gap", properties.getMinNormalizedGapToTruncate());

            List<RankedChildDocument> truncated = truncateRankedDocuments(rankedDocuments == null ? List.of() : rankedDocuments);
            tracing.setAttribute(span, "rag.result.truncated_child_count", truncated.size());
            return truncated;
        });
    }

    private List<RankedChildDocument> truncateRankedDocuments(List<RankedChildDocument> rankedDocuments) {
        if (rankedDocuments.isEmpty()) {
            return List.of();
        }
        if (rankedDocuments.size() <= properties.getMinChildResultsToKeep()) {
            return List.copyOf(rankedDocuments);
        }

        int upperBoundExclusive = Math.min(properties.getMaxChildResultsToConsider(), rankedDocuments.size());
        double topScore = Math.max(rankedDocuments.get(0).score(), 1.0e-9d);
        double largestGap = Double.NEGATIVE_INFINITY;
        int cutIndexExclusive = upperBoundExclusive;

        for (int index = properties.getMinChildResultsToKeep() - 1; index < upperBoundExclusive - 1; index++) {
            double currentScore = rankedDocuments.get(index).score();
            double nextScore = rankedDocuments.get(index + 1).score();
            double normalizedGap = (currentScore - nextScore) / topScore;
            if (normalizedGap > largestGap) {
                largestGap = normalizedGap;
                cutIndexExclusive = index + 1;
            }
        }

        if (largestGap < properties.getMinNormalizedGapToTruncate()) {
            cutIndexExclusive = upperBoundExclusive;
        }
        return List.copyOf(rankedDocuments.subList(0, cutIndexExclusive));
    }

    private List<Document> loadParentDocuments(List<Document> fusedChildDocuments) {
        return tracing.inSpan("rag.parent.load", () -> {
            Span span = tracing.currentSpan();
            List<Document> safeChildren = safeDocuments(fusedChildDocuments);
            tracing.setAttribute(span, "rag.input.child_count", safeChildren.size());
            tracing.captureRagContent(span, "rag.input.children.debug", tracing.debugChildrenJson(safeChildren, 10));
            List<Document> parents = denseRetriever.loadParentDocuments(safeChildren);
            List<Document> safeParents = safeDocuments(parents);
            tracing.setAttribute(span, "rag.result.parent_count", safeParents.size());
            tracing.setAttribute(span, "rag.result.parents", tracing.safeParentsJson(safeParents, 10));
            tracing.captureRagContent(span, "rag.result.parents.debug", tracing.debugParentsJson(safeParents, 10));
            return safeParents;
        });
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

    /**
     * 规范化检索输入文本。
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n").replace('\r', '\n');
    }

    private String rewriteRetrievalQuery(String query) {
        String rewritten = normalizeText(query);
        if (!StringUtils.hasText(rewritten)) {
            return "";
        }

        rewritten = CHINESE_OR_DIGIT_WHITESPACE_PATTERN.matcher(rewritten).replaceAll("");
        rewritten = COMPACT_KEYBOARD_PATTERN.matcher(rewritten).replaceAll("$1键 机械键盘");
        rewritten = KEYBOARD_ATTRIBUTE_PRICE_QUESTION_PATTERN.matcher(rewritten).replaceAll("$1 轴 价格");
        rewritten = MULTIPLE_WHITESPACE_PATTERN.matcher(rewritten).replaceAll(" ").trim();
        return StringUtils.hasText(rewritten) ? rewritten : normalizeText(query);
    }

    private record RankedChildDocument(Document document, double score) {
    }
}
