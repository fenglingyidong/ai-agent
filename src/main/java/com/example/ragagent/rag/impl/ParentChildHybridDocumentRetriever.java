package com.example.ragagent.rag.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Primary
public class ParentChildHybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(ParentChildHybridDocumentRetriever.class);

    private static final int RRF_K = 60;
    private static final int MIN_CHILD_RESULTS_TO_KEEP = 4;
    private static final int MAX_CHILD_RESULTS_TO_CONSIDER = 12;

    @Autowired
    private ParentChildDocumentRetriever denseRetriever;

    @Autowired
    private RedisBm25ChildChunkRetriever bm25Retriever;

    public ParentChildHybridDocumentRetriever() {
    }

    public ParentChildHybridDocumentRetriever(ParentChildDocumentRetriever denseRetriever,
                                              RedisBm25ChildChunkRetriever bm25Retriever) {
        this.denseRetriever = denseRetriever;
        this.bm25Retriever = bm25Retriever;
    }

    /**
     * 对外仍暴露 Spring AI DocumentRetriever，内部先做双路召回，再回查父分块。
     */
    @Override
    public List<Document> retrieve(Query query) {
        String normalizedQuery = normalizeText(query == null ? null : query.text());
        if (!StringUtils.hasText(normalizedQuery)) {
            return List.of();
        }

        List<Document> denseChildDocuments = denseRetriever.retrieveChildDocuments(normalizedQuery);
        List<Document> bm25ChildDocuments = retrieveBm25ChildDocuments(normalizedQuery);
        List<RankedChildDocument> rankedChildDocuments =
                rankWithReciprocalRankFusion(denseChildDocuments, bm25ChildDocuments);
        List<Document> fusedChildDocuments = truncateByLargestNormalizedGap(rankedChildDocuments).stream()
                .map(RankedChildDocument::document)
                .toList();
        if (fusedChildDocuments.isEmpty()) {
            return List.of();
        }

        return denseRetriever.loadParentDocuments(fusedChildDocuments);
    }

    /**
     * BM25 路出现异常时降级为只使用 dense 结果，避免影响主流程。
     */
    private List<Document> retrieveBm25ChildDocuments(String query) {
        try {
            List<Document> documents = bm25Retriever.retrieve(query);
            return documents == null ? List.of() : documents;
        }
        catch (RuntimeException ex) {
            log.warn("BM25 child chunk retrieval failed, falling back to dense-only retrieval", ex);
            return List.of();
        }
    }

    /**
     * 使用 RRF 融合两路 child 排名，避免直接比较不同量纲的分数。
     */
    private List<RankedChildDocument> rankWithReciprocalRankFusion(List<Document> denseDocuments,
                                                                   List<Document> bm25Documents) {
        Map<String, RankedChildDocument> rankedDocuments = new LinkedHashMap<>();
        applyRrfScores(rankedDocuments, denseDocuments);
        applyRrfScores(rankedDocuments, bm25Documents);

        return rankedDocuments.values().stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
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

    /**
     * 复用已有文档对象，缺少文本或元数据时再用另一条召回结果补齐。
     */
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

    private record RankedChildDocument(Document document, double score) {
    }
}
