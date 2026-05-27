package com.example.ragagent.rag;

import com.example.ragagent.config.RagRetrievalProperties;
import com.example.ragagent.rag.impl.MilvusBm25ChildChunkRetriever;
import com.example.ragagent.rag.impl.ParentChildDocumentRetriever;
import com.example.ragagent.rag.impl.ParentChildHybridDocumentRetriever;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParentChildHybridDocumentRetrieverTest {

    @Test
    void retrieveShouldFuseDenseAndBm25ChildResultsBeforeLoadingParents() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        MilvusBm25ChildChunkRetriever bm25Retriever = mock(MilvusBm25ChildChunkRetriever.class);

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
                new ParentChildHybridDocumentRetriever(
                        denseRetriever,
                        bm25Retriever,
                        new RagRetrievalProperties(),
                        Runnable::run
                );

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
        MilvusBm25ChildChunkRetriever bm25Retriever = mock(MilvusBm25ChildChunkRetriever.class);

        Document child1 = childDocument("child-1", "parent-1");
        when(denseRetriever.retrieveChildDocuments("guide")).thenReturn(List.of(child1));
        when(bm25Retriever.retrieve("guide")).thenThrow(new RuntimeException("ft search unavailable"));

        List<Document> parentDocuments = List.of(Document.builder().id("parent-1").text("Parent 1").build());
        when(denseRetriever.loadParentDocuments(List.of(child1))).thenReturn(parentDocuments);

        ParentChildHybridDocumentRetriever retriever =
                new ParentChildHybridDocumentRetriever(
                        denseRetriever,
                        bm25Retriever,
                        new RagRetrievalProperties(),
                        Runnable::run
                );

        List<Document> documents = retriever.retrieve(new Query("guide"));

        assertEquals(parentDocuments, documents);
        verify(denseRetriever).loadParentDocuments(List.of(child1));
    }

    @Test
    void retrieveShouldTruncateAtLargestNormalizedGapWithinTopTen() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        MilvusBm25ChildChunkRetriever bm25Retriever = mock(MilvusBm25ChildChunkRetriever.class);

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
                new ParentChildHybridDocumentRetriever(
                        denseRetriever,
                        bm25Retriever,
                        new RagRetrievalProperties(),
                        Runnable::run
                );

        retriever.retrieve(new Query("guide"));

        ArgumentCaptor<List> childDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(denseRetriever).loadParentDocuments(childDocumentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Document> truncatedChildDocuments = childDocumentsCaptor.getValue();
        assertEquals(List.of("child-1", "child-2", "child-3", "child-4", "child-5", "child-6"),
                truncatedChildDocuments.stream().map(Document::getId).toList());
    }

    @Test
    void retrieveShouldStartDenseAndBm25InParallel() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        MilvusBm25ChildChunkRetriever bm25Retriever = mock(MilvusBm25ChildChunkRetriever.class);
        CountDownLatch bm25Started = new CountDownLatch(1);
        Document denseChild = childDocument("child-1", "parent-1");
        Document bm25Child = childDocument("child-2", "parent-2");

        when(denseRetriever.retrieveChildDocuments("guide")).thenAnswer(invocation -> {
            assertTrue(bm25Started.await(500, TimeUnit.MILLISECONDS));
            return List.of(denseChild);
        });
        when(bm25Retriever.retrieve("guide")).thenAnswer(invocation -> {
            bm25Started.countDown();
            return List.of(bm25Child);
        });
        when(denseRetriever.loadParentDocuments(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(Document.builder().id("parent-1").text("Parent 1").build()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ParentChildHybridDocumentRetriever retriever =
                    new ParentChildHybridDocumentRetriever(
                            denseRetriever,
                            bm25Retriever,
                            new RagRetrievalProperties(),
                            executor
                    );

            retriever.retrieve(new Query("guide"));
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retrieveShouldReturnDenseWhenBm25DoesNotFinishBeforeTimeout() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        MilvusBm25ChildChunkRetriever bm25Retriever = mock(MilvusBm25ChildChunkRetriever.class);
        Document denseChild = childDocument("child-1", "parent-1");
        List<Document> parentDocuments = List.of(Document.builder().id("parent-1").text("Parent 1").build());
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setBm25FutureTimeoutMs(50);

        when(denseRetriever.retrieveChildDocuments("guide")).thenReturn(List.of(denseChild));
        when(bm25Retriever.retrieve("guide")).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return List.of(childDocument("child-2", "parent-2"));
        });
        when(denseRetriever.loadParentDocuments(List.of(denseChild))).thenReturn(parentDocuments);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ParentChildHybridDocumentRetriever retriever =
                    new ParentChildHybridDocumentRetriever(
                            denseRetriever,
                            bm25Retriever,
                            properties,
                            executor
                    );

            long startedAt = System.nanoTime();
            List<Document> documents = retriever.retrieve(new Query("guide"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(parentDocuments, documents);
            assertTrue(elapsedMs < 500);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retrieveShouldKeepDenseOffRejectedBm25Executor() {
        ParentChildDocumentRetriever denseRetriever = mock(ParentChildDocumentRetriever.class);
        MilvusBm25ChildChunkRetriever bm25Retriever = mock(MilvusBm25ChildChunkRetriever.class);
        Document denseChild = childDocument("child-1", "parent-1");
        List<Document> parentDocuments = List.of(Document.builder().id("parent-1").text("Parent 1").build());
        AtomicInteger executorSubmissions = new AtomicInteger();

        when(denseRetriever.retrieveChildDocuments("guide")).thenReturn(List.of(denseChild));
        when(denseRetriever.loadParentDocuments(List.of(denseChild))).thenReturn(parentDocuments);

        ParentChildHybridDocumentRetriever retriever =
                new ParentChildHybridDocumentRetriever(
                        denseRetriever,
                        bm25Retriever,
                        new RagRetrievalProperties(),
                        command -> {
                            executorSubmissions.incrementAndGet();
                            throw new RejectedExecutionException("queue full");
                        }
                );

        List<Document> documents = retriever.retrieve(new Query("guide"));

        assertEquals(parentDocuments, documents);
        assertEquals(1, executorSubmissions.get());
        verify(denseRetriever).retrieveChildDocuments("guide");
        verify(denseRetriever).loadParentDocuments(List.of(denseChild));
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
