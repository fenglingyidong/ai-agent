package com.example.ragagent.rag;

import com.example.ragagent.rag.impl.ParentChildDocumentRetriever;
import com.example.ragagent.rag.impl.ParentChildHybridDocumentRetriever;
import com.example.ragagent.rag.impl.RedisBm25ChildChunkRetriever;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParentChildHybridDocumentRetrieverTest {

    @Test
    void retrieveShouldFuseDenseAndBm25ChildResultsBeforeLoadingParents() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        RedisBm25ChildChunkRetriever bm25Retriever = mock(RedisBm25ChildChunkRetriever.class);

        Document child1 = childDocument("child-1", "parent-1");
        Document child2 = childDocument("child-2", "parent-2");

        when(denseRetriever.retrieveChildDocuments("guide")).thenReturn(List.of(child1, child2));
        when(bm25Retriever.retrieve("guide")).thenReturn(List.of(child2));

        List<Document> parentDocuments = List.of(
                Document.builder().id("parent-2").text("Parent 2").build(),
                Document.builder().id("parent-1").text("Parent 1").build()
        );
        when(denseRetriever.loadParentDocuments(org.mockito.ArgumentMatchers.anyList())).thenReturn(parentDocuments);

        ParentChildHybridDocumentRetriever retriever =
                new ParentChildHybridDocumentRetriever(denseRetriever, bm25Retriever);

        List<Document> documents = retriever.retrieve(new Query("guide"));

        assertEquals(parentDocuments, documents);

        ArgumentCaptor<List> childDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(denseRetriever).loadParentDocuments(childDocumentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Document> fusedChildDocuments = childDocumentsCaptor.getValue();
        assertEquals(List.of("child-2", "child-1"),
                fusedChildDocuments.stream().map(Document::getId).toList());
    }

    @Test
    void retrieveShouldFallBackToDenseOnlyWhenBm25Fails() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        RedisBm25ChildChunkRetriever bm25Retriever = mock(RedisBm25ChildChunkRetriever.class);

        Document child1 = childDocument("child-1", "parent-1");
        when(denseRetriever.retrieveChildDocuments("guide")).thenReturn(List.of(child1));
        when(bm25Retriever.retrieve("guide")).thenThrow(new RuntimeException("ft search unavailable"));

        List<Document> parentDocuments = List.of(Document.builder().id("parent-1").text("Parent 1").build());
        when(denseRetriever.loadParentDocuments(List.of(child1))).thenReturn(parentDocuments);

        ParentChildHybridDocumentRetriever retriever =
                new ParentChildHybridDocumentRetriever(denseRetriever, bm25Retriever);

        List<Document> documents = retriever.retrieve(new Query("guide"));

        assertEquals(parentDocuments, documents);
        verify(denseRetriever).loadParentDocuments(List.of(child1));
    }

    @Test
    void retrieveShouldTruncateAtLargestNormalizedGapWithinTopTen() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        RedisBm25ChildChunkRetriever bm25Retriever = mock(RedisBm25ChildChunkRetriever.class);

        List<Document> denseChildren = List.of(
                childDocument("child-1", "parent-1"),
                childDocument("child-2", "parent-2"),
                childDocument("child-3", "parent-3"),
                childDocument("child-4", "parent-4"),
                childDocument("child-5", "parent-5"),
                childDocument("child-6", "parent-6"),
                childDocument("child-7", "parent-7"),
                childDocument("child-8", "parent-8"),
                childDocument("child-9", "parent-9"),
                childDocument("child-10", "parent-10")
        );
        List<Document> bm25Children = List.of(
                childDocument("child-1", "parent-1"),
                childDocument("child-2", "parent-2"),
                childDocument("child-3", "parent-3"),
                childDocument("child-4", "parent-4"),
                childDocument("child-5", "parent-5"),
                childDocument("child-6", "parent-6")
        );

        when(denseRetriever.retrieveChildDocuments("guide")).thenReturn(denseChildren);
        when(bm25Retriever.retrieve("guide")).thenReturn(bm25Children);
        when(denseRetriever.loadParentDocuments(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());

        ParentChildHybridDocumentRetriever retriever =
                new ParentChildHybridDocumentRetriever(denseRetriever, bm25Retriever);

        retriever.retrieve(new Query("guide"));

        ArgumentCaptor<List> childDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(denseRetriever).loadParentDocuments(childDocumentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Document> truncatedChildDocuments = childDocumentsCaptor.getValue();
        assertEquals(List.of("child-1", "child-2", "child-3", "child-4", "child-5", "child-6"),
                truncatedChildDocuments.stream().map(Document::getId).toList());
    }

    private Document childDocument(String childId, String parentId) {
        return Document.builder()
                .id(childId)
                .text("child")
                .metadata(Map.of(
                        "docType", "rag-child",
                        "parentId", parentId
                ))
                .build();
    }
}
