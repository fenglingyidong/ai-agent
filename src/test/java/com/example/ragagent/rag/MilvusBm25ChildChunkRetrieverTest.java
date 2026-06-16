package com.example.ragagent.rag;

import com.example.ragagent.config.AppVectorProperties;
import com.example.ragagent.config.RagRetrievalProperties;
import com.example.ragagent.rag.impl.MilvusBm25ChildChunkRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusBm25ChildChunkRetrieverTest {

    @Test
    void retrieveShouldParseGsonJsonObjectMetadata() {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("docType", "rag-child");
        metadata.addProperty("skuId", "3020");
        MilvusClientV2 milvusClientV2 = milvusClientReturning(metadata);
        MilvusBm25ChildChunkRetriever retriever = retriever(milvusClientV2);

        List<Document> documents = retriever.retrieve("儿童积木");

        assertEquals(1, documents.size());
        assertEquals("child-1", documents.get(0).getId());
        assertEquals("3020", documents.get(0).getMetadata().get("skuId"));
        assertEquals(1.5f, documents.get(0).getMetadata().get("bm25Score"));
    }

    @Test
    void retrieveShouldParseJsonStringMetadata() {
        MilvusClientV2 milvusClientV2 = milvusClientReturning("""
                {"docType":"rag-child","skuId":"3020","brand":"启蒙"}
                """.trim());
        MilvusBm25ChildChunkRetriever retriever = retriever(milvusClientV2);

        List<Document> documents = retriever.retrieve("儿童积木");

        assertEquals(1, documents.size());
        assertEquals("启蒙", documents.get(0).getMetadata().get("brand"));
    }

    @Test
    void retrieveShouldUseDocIdOutputWhenSearchResultIdIsMissing() {
        MilvusClientV2 milvusClientV2 = mock(MilvusClientV2.class);
        SearchResp.SearchResult searchResult = SearchResp.SearchResult.builder()
                .score(1.5f)
                .entity(Map.of(
                        MilvusVectorStore.DOC_ID_FIELD_NAME, "child-from-doc-id",
                        MilvusVectorStore.CONTENT_FIELD_NAME, "儿童积木套装",
                        MilvusVectorStore.METADATA_FIELD_NAME, Map.of("docType", "rag-child")
                ))
                .build();
        when(milvusClientV2.search(any(SearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(searchResult)))
                .build());
        MilvusBm25ChildChunkRetriever retriever = retriever(milvusClientV2);

        List<Document> documents = retriever.retrieve("儿童积木");

        assertEquals(1, documents.size());
        assertEquals("child-from-doc-id", documents.get(0).getId());
    }

    @Test
    void retrieveShouldRequestDocIdFieldFromMilvus() {
        MilvusClientV2 milvusClientV2 = milvusClientReturning(Map.of("docType", "rag-child"));
        MilvusBm25ChildChunkRetriever retriever = retriever(milvusClientV2);

        retriever.retrieve("儿童积木");

        ArgumentCaptor<SearchReq> requestCaptor = ArgumentCaptor.forClass(SearchReq.class);
        verify(milvusClientV2).search(requestCaptor.capture());
        assertEquals(List.of(
                MilvusVectorStore.DOC_ID_FIELD_NAME,
                MilvusVectorStore.CONTENT_FIELD_NAME,
                MilvusVectorStore.METADATA_FIELD_NAME
        ), requestCaptor.getValue().getOutputFields());
    }

    private MilvusBm25ChildChunkRetriever retriever(MilvusClientV2 milvusClientV2) {
        return new MilvusBm25ChildChunkRetriever(
                milvusClientV2,
                new AppVectorProperties(),
                new RagRetrievalProperties(),
                new ObjectMapper()
        );
    }

    private MilvusClientV2 milvusClientReturning(Object metadata) {
        MilvusClientV2 milvusClientV2 = mock(MilvusClientV2.class);
        when(milvusClientV2.search(any(SearchReq.class))).thenReturn(searchResponse(metadata));
        return milvusClientV2;
    }

    private SearchResp searchResponse(Object metadata) {
        SearchResp.SearchResult searchResult = SearchResp.SearchResult.builder()
                .id("child-1")
                .score(1.5f)
                .entity(Map.of(
                        MilvusVectorStore.DOC_ID_FIELD_NAME, "child-1",
                        MilvusVectorStore.CONTENT_FIELD_NAME, "儿童积木套装",
                        MilvusVectorStore.METADATA_FIELD_NAME, metadata
                ))
                .build();
        return SearchResp.builder()
                .searchResults(List.of(List.of(searchResult)))
                .build();
    }
}
