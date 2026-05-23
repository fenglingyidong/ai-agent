package com.example.ragagent.rag.impl;

import com.example.ragagent.config.AppVectorProperties;
import com.example.ragagent.config.MilvusVectorStoreConfiguration;
import com.example.ragagent.config.RagRetrievalProperties;
import com.example.ragagent.rag.RagDocumentConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MilvusBm25ChildChunkRetriever {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final MilvusClientV2 milvusClientV2;
    private final AppVectorProperties vectorProperties;
    private final RagRetrievalProperties retrievalProperties;
    private final ObjectMapper objectMapper;

    public MilvusBm25ChildChunkRetriever(MilvusClientV2 milvusClientV2,
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
        return Document.builder()
                .id(searchResult.getId() == null ? "" : searchResult.getId().toString())
                .text(readString(entity.get(MilvusVectorStore.CONTENT_FIELD_NAME)))
                .metadata(metadata)
                .score(searchResult.getScore() == null ? null : searchResult.getScore().doubleValue())
                .build();
    }

    private Map<String, Object> readMetadata(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(objectMapper.convertValue(value, METADATA_TYPE));
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
