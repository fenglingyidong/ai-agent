package com.example.ragagent.rag.impl;

import com.example.ragagent.config.AppVectorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 使用 Milvus V2 原生 insert 写入商品子分块，同时写入 dense 向量和可 BM25 检索的文本。
 */
@Component
public class MilvusProductChildDocumentWriter implements ProductChildDocumentWriter {

    private final MilvusClientV2 milvusClientV2;
    private final EmbeddingModel embeddingModel;
    private final AppVectorProperties properties;
    private final ObjectMapper objectMapper;

    public MilvusProductChildDocumentWriter(MilvusClientV2 milvusClientV2,
                                            EmbeddingModel embeddingModel,
                                            AppVectorProperties properties,
                                            ObjectMapper objectMapper) {
        this.milvusClientV2 = milvusClientV2;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 为子分块生成 embedding，并批量写入商品集合。
     */
    @Override
    public void add(List<Document> documents) {
        List<Document> safeDocuments = documents == null ? List.of() : documents.stream()
                .filter(document -> document != null && StringUtils.hasText(document.getText()))
                .toList();
        if (safeDocuments.isEmpty()) {
            return;
        }

        List<float[]> embeddings = embeddingModel.embed(safeDocuments.stream()
                .map(Document::getText)
                .toList());
        if (embeddings.size() != safeDocuments.size()) {
            throw new IllegalStateException("embedding count does not match child document count");
        }

        List<JsonObject> rows = new ArrayList<>(safeDocuments.size());
        for (int index = 0; index < safeDocuments.size(); index++) {
            rows.add(toInsertRow(safeDocuments.get(index), embeddings.get(index)));
        }

        milvusClientV2.insert(InsertReq.builder()
                .collectionName(properties.getProduct().getCollectionName())
                .data(rows)
                .build());
    }

    private JsonObject toInsertRow(Document document, float[] embedding) {
        if (!StringUtils.hasText(document.getId())) {
            throw new IllegalStateException("child document id must not be blank");
        }
        JsonObject row = new JsonObject();
        row.addProperty(MilvusVectorStore.DOC_ID_FIELD_NAME, document.getId());
        row.addProperty(MilvusVectorStore.CONTENT_FIELD_NAME, document.getText());
        row.add(MilvusVectorStore.METADATA_FIELD_NAME, toJsonObject(document.getMetadata()));
        row.add(MilvusVectorStore.EMBEDDING_FIELD_NAME, toJsonArray(embedding));
        return row;
    }

    private JsonObject toJsonObject(Map<String, Object> metadata) {
        try {
            String json = objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
            return JsonParser.parseString(json).getAsJsonObject();
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize child document metadata", ex);
        }
    }

    private JsonArray toJsonArray(float[] embedding) {
        if (embedding == null || embedding.length != properties.getMilvus().getEmbeddingDimension()) {
            throw new IllegalStateException("embedding dimension does not match Milvus schema");
        }
        JsonArray array = new JsonArray(embedding.length);
        for (float value : embedding) {
            array.add(value);
        }
        return array;
    }
}
