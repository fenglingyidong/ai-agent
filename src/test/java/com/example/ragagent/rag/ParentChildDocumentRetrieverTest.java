package com.example.ragagent.rag;

import com.example.ragagent.rag.impl.ParentChildDocumentRetriever;
import com.example.ragagent.rag.impl.ParentDocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParentChildDocumentRetrieverTest {

    @Test
    void retrieveShouldLoadParentDocumentsFromVectorStoreHits() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);

        Document childHit = Document.builder()
                .id("parent-1-child-0")
                .text("Dense child")
                .metadata(java.util.Map.of(
                        "docType", "rag-child",
                        "parentId", "parent-1",
                        "sourceId", "source-1",
                        "title", "Guide"
                ))
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(childHit));

        Document parentDocument = Document.builder()
                .id("parent-1")
                .text("Parent chunk sentence one. Parent chunk sentence two.")
                .metadata(java.util.Map.of(
                        "parentId", "parent-1",
                        "sourceId", "source-1",
                        "title", "Guide"
                ))
                .build();
        when(parentDocumentStore.load("parent-1")).thenReturn(Optional.of(parentDocument));

        ParentChildDocumentRetriever retriever = new ParentChildDocumentRetriever(vectorStore, parentDocumentStore);

        List<Document> documents = retriever.retrieve(new Query("guide sentence"));

        assertEquals(1, documents.size());
        assertEquals("Parent chunk sentence one. Parent chunk sentence two.", documents.get(0).getText());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
        verify(parentDocumentStore).load("parent-1");
    }

    @Test
    void retrieveShouldReturnEmptyWhenNoHitIsFound() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ParentChildDocumentRetriever retriever = new ParentChildDocumentRetriever(vectorStore, parentDocumentStore);

        List<Document> documents = retriever.retrieve(new Query("missing"));

        assertTrue(documents.isEmpty());
    }

    @Test
    void retrieveShouldFallBackWhenFilteredRetrieverPathFails() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);

        Document childHit = Document.builder()
                .id("parent-1-child-0")
                .text("Dense child")
                .metadata(java.util.Map.of(
                        "docType", "rag-child",
                        "parentId", "parent-1",
                        "sourceId", "source-1",
                        "title", "Guide"
                ))
                .build();
        Document memoryHit = Document.builder()
                .id("memory-1")
                .text("Long-term memory")
                .metadata(java.util.Map.of(
                        "memoryType", "summary",
                        "userId", "demo"
                ))
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("syntax error"))
                .thenReturn(List.of(memoryHit, childHit));

        Document parentDocument = Document.builder()
                .id("parent-1")
                .text("Parent chunk sentence one. Parent chunk sentence two.")
                .metadata(java.util.Map.of(
                        "parentId", "parent-1",
                        "sourceId", "source-1",
                        "title", "Guide"
                ))
                .build();
        when(parentDocumentStore.load("parent-1")).thenReturn(Optional.of(parentDocument));

        ParentChildDocumentRetriever retriever = new ParentChildDocumentRetriever(vectorStore, parentDocumentStore);

        List<Document> documents = retriever.retrieve(new Query("guide sentence"));

        assertEquals(1, documents.size());
        assertEquals("parent-1", documents.get(0).getId());
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
        verify(parentDocumentStore).load("parent-1");
    }
}
