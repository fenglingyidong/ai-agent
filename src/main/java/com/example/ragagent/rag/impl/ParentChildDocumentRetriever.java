package com.example.ragagent.rag.impl;

import com.example.ragagent.config.MilvusVectorStoreConfiguration;
import com.example.ragagent.config.RagRetrievalProperties;
import com.example.ragagent.rag.RagDocumentConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dense 父子分块检索器，先召回子分块，再按 parentId 回查父文档。
 */
@Component
public class ParentChildDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(ParentChildDocumentRetriever.class);

    private final VectorStore vectorStore;
    private final ParentDocumentStore parentDocumentStore;
    private final RagRetrievalProperties properties;
    private final VectorStoreDocumentRetriever childDocumentRetriever;

    /**
     * 创建父子分块检索器，先查子分块，再回查对应父分块。
     */
    public ParentChildDocumentRetriever(@Qualifier(MilvusVectorStoreConfiguration.PRODUCT_VECTOR_STORE) VectorStore vectorStore,
                                        ParentDocumentStore parentDocumentStore,
                                        RagRetrievalProperties properties) {
        this.vectorStore = vectorStore;
        this.parentDocumentStore = parentDocumentStore;
        this.properties = properties;
        this.childDocumentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(properties.getDenseChildTopK())
                .similarityThreshold(properties.getDenseSimilarityThreshold())
                .filterExpression(new FilterExpressionBuilder()
                        .eq(RagDocumentConstants.METADATA_DOC_TYPE, RagDocumentConstants.CHILD_DOCUMENT_TYPE)
                        .build())
                .build();
    }

    /**
     * 检索当前问题相关的父文档列表。
     */
    @Override
    public List<Document> retrieve(Query query) {
        String normalizedQuery = normalizeText(query == null ? null : query.text());
        if (!StringUtils.hasText(normalizedQuery)) {
            return List.of();
        }

        List<Document> childDocuments = retrieveChildDocuments(normalizedQuery);
        if (childDocuments.isEmpty()) {
            return List.of();
        }

        return loadParentDocuments(childDocuments);
    }

    /**
     * 优先走 Spring AI 官方检索器，失败时退回普通向量相似度检索。
     */
    public List<Document> retrieveChildDocuments(Query query) {
        return retrieveChildDocuments(query == null ? null : query.text());
    }

    /**
     * 优先走 Spring AI 官方检索器，失败时退回普通向量相似度检索。
     */
    public List<Document> retrieveChildDocuments(String query) {
        String normalizedQuery = normalizeText(query);
        if (!StringUtils.hasText(normalizedQuery)) {
            return List.of();
        }

        try {
            List<Document> documents = childDocumentRetriever.retrieve(new Query(normalizedQuery));
            return documents == null ? List.of() : List.copyOf(documents);
        }
        catch (RuntimeException ex) {
            log.warn("VectorStoreDocumentRetriever failed, falling back to plain similarity search", ex);
            return fallbackSimilaritySearch(normalizedQuery);
        }
    }

    /**
     * 向量检索兜底时，在应用层过滤出 child 文档。
     */
    private List<Document> fallbackSimilaritySearch(String query) {
        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(properties.getDenseFallbackTopK())
                            .build()
            );
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            return documents.stream()
                    .filter(this::isChildDocument)
                    .limit(properties.getDenseChildTopK())
                    .toList();
        }
        catch (RuntimeException ex) {
            log.warn("Fallback similarity search failed", ex);
            return List.of();
        }
    }

    /**
     * 按子分块命中顺序回查去重后的父分块。
     */
    public List<Document> loadParentDocuments(List<Document> childDocuments) {
        Map<String, Document> parentDocuments = new LinkedHashMap<>();
        for (Document childDocument : childDocuments) {
            String parentId = readMetadataValue(childDocument, RagDocumentConstants.METADATA_PARENT_ID);
            if (!StringUtils.hasText(parentId) || parentDocuments.containsKey(parentId)) {
                continue;
            }

            parentDocumentStore.load(parentId)
                    .ifPresent(parent -> parentDocuments.put(parentId, parent));

            if (parentDocuments.size() >= properties.getMaxParentResults()) {
                break;
            }
        }
        return List.copyOf(parentDocuments.values());
    }

    /**
     * 安全读取文档中的单个元数据字段。
     */
    private String readMetadataValue(Document document, String key) {
        if (document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    /**
     * 判断检索命中的文档是否属于 RAG 子分块。
     */
    private boolean isChildDocument(Document document) {
        return RagDocumentConstants.CHILD_DOCUMENT_TYPE.equals(
                readMetadataValue(document, RagDocumentConstants.METADATA_DOC_TYPE)
        );
    }

    /**
     * 规范化检索输入文本，统一空白和换行形式。
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n").replace('\r', '\n');
    }
}
