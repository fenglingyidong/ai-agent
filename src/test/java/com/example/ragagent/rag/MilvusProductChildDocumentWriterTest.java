package com.example.ragagent.rag;

import com.example.ragagent.config.AppVectorProperties;
import com.example.ragagent.rag.impl.MilvusProductChildDocumentWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MilvusProductChildDocumentWriterTest {

    @Test
    void addShouldInsertDenseFieldsAndLetMilvusGenerateSparseVector() {
        MilvusClientV2 milvusClientV2 = mock(MilvusClientV2.class);
        AppVectorProperties properties = new AppVectorProperties();
        properties.getMilvus().setEmbeddingDimension(3);
        EmbeddingModel embeddingModel = new FixedEmbeddingModel();
        MilvusProductChildDocumentWriter writer = new MilvusProductChildDocumentWriter(
                milvusClientV2,
                embeddingModel,
                properties,
                new ObjectMapper()
        );
        Document document = Document.builder()
                .id("child-1")
                .text("儿童积木套装 300片")
                .metadata(Map.of("docType", "rag-child", "parentId", "parent-1"))
                .build();

        writer.add(List.of(document));

        ArgumentCaptor<InsertReq> requestCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClientV2).insert(requestCaptor.capture());
        InsertReq request = requestCaptor.getValue();
        JsonObject row = request.getData().get(0);
        assertEquals("product_index", request.getCollectionName());
        assertEquals("child-1", row.get("doc_id").getAsString());
        assertEquals("儿童积木套装 300片", row.get("content").getAsString());
        assertTrue(row.has("metadata"));
        assertTrue(row.has("embedding"));
        assertFalse(row.has("sparse_vector"));
    }

    private static final class FixedEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(List.of(new Embedding(new float[]{0.1f, 0.2f, 0.3f}, 0)));
        }

        @Override
        public float[] embed(Document document) {
            return new float[]{0.1f, 0.2f, 0.3f};
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream()
                    .map(ignored -> new float[]{0.1f, 0.2f, 0.3f})
                    .toList();
        }
    }
}
