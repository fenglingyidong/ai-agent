package com.example.ragagent.rag.impl;

import com.example.ragagent.config.AppVectorProperties;
import com.example.ragagent.config.MilvusVectorStoreConfiguration;
import com.example.ragagent.config.RagRetrievalProperties;
import com.example.ragagent.rag.RagDocumentConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直接调用 Milvus V2 BM25 sparse vector 查询商品子分块。
 */
@Component
public class MilvusBm25ChildChunkRetriever {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final MilvusClientV2 milvusClientV2;
    private final AppVectorProperties vectorProperties;
    private final RagRetrievalProperties retrievalProperties;
    private final ObjectMapper objectMapper;

    public MilvusBm25ChildChunkRetriever(
            @Qualifier(MilvusVectorStoreConfiguration.BM25_MILVUS_CLIENT) MilvusClientV2 milvusClientV2,
            AppVectorProperties vectorProperties,
            RagRetrievalProperties retrievalProperties,
            ObjectMapper objectMapper) {
        this.milvusClientV2 = milvusClientV2;
        this.vectorProperties = vectorProperties;
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用 Milvus 2.5+ BM25 Function 生成的 sparse vector 做关键词召回。
     */
    public List<Document> retrieve(String query) {
        String normalizedQuery = normalizeText(query);
        if (!vectorProperties.getProduct().isBm25Enabled() || !StringUtils.hasText(normalizedQuery)) {
            return List.of();
        }

        SearchResp searchResp = milvusClientV2.search(SearchReq.builder()
                .collectionName(vectorProperties.getProduct().getCollectionName())
                .annsField(MilvusVectorStoreConfiguration.SPARSE_VECTOR_FIELD)
                .metricType(IndexParam.MetricType.BM25)
                .topK(retrievalProperties.getBm25ChildTopK())
                .filter("metadata[\"" + RagDocumentConstants.METADATA_DOC_TYPE + "\"] == \""
                        + RagDocumentConstants.CHILD_DOCUMENT_TYPE + "\"")
                .outputFields(List.of(
                        MilvusVectorStore.DOC_ID_FIELD_NAME,
                        MilvusVectorStore.CONTENT_FIELD_NAME,
                        MilvusVectorStore.METADATA_FIELD_NAME
                ))
                .data(List.of(new EmbeddedText(normalizedQuery)))
                .build());
        if (searchResp == null || searchResp.getSearchResults() == null || searchResp.getSearchResults().isEmpty()) {
            return List.of();
        }

        return searchResp.getSearchResults().get(0).stream()
                .map(this::toSpringDocument)
                .filter(this::isChildDocument)
                .toList();
    }

    private Document toSpringDocument(SearchResp.SearchResult searchResult) {
        Map<String, Object> entity = searchResult.getEntity() == null ? Map.of() : searchResult.getEntity();
        Map<String, Object> metadata = readMetadata(entity.get(MilvusVectorStore.METADATA_FIELD_NAME));
        if (searchResult.getScore() != null) {
            metadata.put("bm25Score", searchResult.getScore());
        }
        String documentId = readString(searchResult.getId());
        if (!StringUtils.hasText(documentId)) {
            documentId = readString(entity.get(MilvusVectorStore.DOC_ID_FIELD_NAME));
        }
        return Document.builder()
                .id(documentId)
                .text(readString(entity.get(MilvusVectorStore.CONTENT_FIELD_NAME)))
                .metadata(metadata)
                .score(searchResult.getScore() == null ? null : searchResult.getScore().doubleValue())
                .build();
    }

    private Map<String, Object> readMetadata(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>(objectMapper.convertValue(map, METADATA_TYPE));
        }
        if (value instanceof JsonElement jsonElement) {
            return readMetadataFromJsonElement(jsonElement);
        }
        if (value instanceof String text) {
            return readMetadataFromJson(text);
        }
        return new LinkedHashMap<>(objectMapper.convertValue(value, METADATA_TYPE));
    }

    private Map<String, Object> readMetadataFromJsonElement(JsonElement jsonElement) {
        if (jsonElement == null || jsonElement.isJsonNull()) {
            return new LinkedHashMap<>();
        }
        if (jsonElement instanceof JsonPrimitive primitive && primitive.isString()) {
            return readMetadataFromJson(primitive.getAsString());
        }
        return readMetadataFromJson(jsonElement.toString());
    }

    private Map<String, Object> readMetadataFromJson(String text) {
        if (!StringUtils.hasText(text)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(text, METADATA_TYPE));
        }
        catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private boolean isChildDocument(Document document) {
        Object value = document.getMetadata().get(RagDocumentConstants.METADATA_DOC_TYPE);
        return RagDocumentConstants.CHILD_DOCUMENT_TYPE.equals(value == null ? "" : value.toString());
    }

    private String readString(Object value) {
        return value == null ? "" : value.toString();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n").replace('\r', '\n');
    }
}
