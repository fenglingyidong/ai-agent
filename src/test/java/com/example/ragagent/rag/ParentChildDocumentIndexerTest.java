package com.example.ragagent.rag;

import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import com.example.ragagent.rag.impl.ParentDocumentStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ParentChildDocumentIndexerTest {

    @Test
    void indexDocumentShouldStoreParentsAndIndexChildren() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);
        ParentChildDocumentIndexer indexer = new ParentChildDocumentIndexer(vectorStore, parentDocumentStore);

        ParentChildDocumentIndexer.DocumentIndexingResult indexingResult = indexer.indexDocumentDetails(
                "source-1",
                "Guide",
                "First paragraph. Another sentence.\n\nSecond paragraph. Final sentence."
        );

        ArgumentCaptor<List> childDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(childDocumentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Document> childDocuments = childDocumentsCaptor.getValue();

        assertFalse(indexingResult.parentIds().isEmpty());
        assertFalse(indexingResult.childIds().isEmpty());
        assertEquals(childDocuments.size(), indexingResult.childIds().size());
        assertEquals("rag-child", childDocuments.get(0).getMetadata().get("docType"));
        assertTrue(childDocuments.get(0).getMetadata().containsKey("parentId"));
        assertTrue(childDocuments.get(0).getMetadata().containsKey("documentHash"));
        assertTrue(childDocuments.get(0).getMetadata().containsKey("parentIndex"));
        verify(vectorStore).delete(org.mockito.ArgumentMatchers.any(Filter.Expression.class));
        verify(parentDocumentStore).deleteBySourceId("source-1");
        verify(parentDocumentStore, atLeastOnce()).save(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("source-1"),
                org.mockito.ArgumentMatchers.eq("Guide"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Map.of())
        );
    }

    @Test
    void indexParentChunkShouldReturnGeneratedParentId() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);
        ParentChildDocumentIndexer indexer = new ParentChildDocumentIndexer(vectorStore, parentDocumentStore);

        String parentId = indexer.indexParentChunk("source-1", "Guide", "Parent text.");

        assertFalse(parentId.isBlank());
        verify(vectorStore).delete(org.mockito.ArgumentMatchers.any(Filter.Expression.class));
        verify(parentDocumentStore).deleteBySourceId("source-1");
        verify(parentDocumentStore).save(
                parentId,
                "source-1",
                "Guide",
                "Parent text.",
                0,
                "5be0ad65a776b8a3e0619539967a00dc6f8b123082125a0f2c17f7ca7349b62b",
                Map.of()
        );
    }

    @Test
    void indexParentChunkShouldGenerateChildChunksForChineseContent() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);
        ParentChildDocumentIndexer indexer = new ParentChildDocumentIndexer(vectorStore, parentDocumentStore);

        indexer.indexParentChunk("source-zh", "中文指南", "第一句。这是第二句。这是第三句。这是第四句。");

        ArgumentCaptor<List> childDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(childDocumentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Document> childDocuments = childDocumentsCaptor.getValue();

        assertFalse(childDocuments.isEmpty());
        assertTrue(childDocuments.stream().allMatch(document -> document.getText() != null && !document.getText().isBlank()));
        assertTrue(childDocuments.stream().allMatch(document ->
                "rag-child".equals(document.getMetadata().get("docType"))));
    }

    @Test
    void indexParentChunkShouldGenerateStableParentIdForSameSourceAndContent() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);
        ParentChildDocumentIndexer indexer = new ParentChildDocumentIndexer(vectorStore, parentDocumentStore);

        String firstParentId = indexer.indexParentChunk("source-stable", "Guide", "Same parent text.");
        String secondParentId = indexer.indexParentChunk("source-stable", "Guide", "Same parent text.");

        assertEquals(firstParentId, secondParentId);
        verify(vectorStore, times(2)).delete(org.mockito.ArgumentMatchers.any(Filter.Expression.class));
        verify(parentDocumentStore, times(2)).deleteBySourceId("source-stable");
    }

    @Test
    void indexingSameSourceShouldSerializeOverwriteAndWrites() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);
        ParentChildDocumentIndexer indexer = new ParentChildDocumentIndexer(vectorStore, parentDocumentStore);

        CountDownLatch firstDeleteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDelete = new CountDownLatch(1);
        CountDownLatch secondTaskStarted = new CountDownLatch(1);
        CountDownLatch secondDeleteStarted = new CountDownLatch(1);
        AtomicInteger deleteCalls = new AtomicInteger();

        doAnswer(invocation -> {
            int call = deleteCalls.incrementAndGet();
            if (call == 1) {
                firstDeleteStarted.countDown();
                assertTrue(releaseFirstDelete.await(5, TimeUnit.SECONDS));
            }
            else if (call == 2) {
                secondDeleteStarted.countDown();
            }
            return null;
        }).when(parentDocumentStore).deleteBySourceId("source-1");

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstFuture = executorService.submit(() ->
                    indexer.indexParentChunk("source-1", "Guide A", "First parent text."));

            assertTrue(firstDeleteStarted.await(5, TimeUnit.SECONDS));

            Future<String> secondFuture = executorService.submit(() -> {
                secondTaskStarted.countDown();
                return indexer.indexParentChunk("source-1", "Guide B", "Second parent text.");
            });

            assertTrue(secondTaskStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondDeleteStarted.await(400, TimeUnit.MILLISECONDS));

            releaseFirstDelete.countDown();
            firstFuture.get(5, TimeUnit.SECONDS);
            secondFuture.get(5, TimeUnit.SECONDS);
        }
        finally {
            releaseFirstDelete.countDown();
            executorService.shutdownNow();
        }

        verify(parentDocumentStore, times(2)).deleteBySourceId("source-1");
        verify(parentDocumentStore, times(2)).save(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("source-1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Map.of())
        );
    }

    @Test
    void indexDocumentShouldAttachProductMetadataToChildChunksAndParentStore() {
        VectorStore vectorStore = mock(VectorStore.class);
        ParentDocumentStore parentDocumentStore = mock(ParentDocumentStore.class);
        ParentChildDocumentIndexer indexer = new ParentChildDocumentIndexer(vectorStore, parentDocumentStore);

        indexer.indexDocumentDetails(
                "product-P1001",
                "云跑 AirLite 缓震跑步鞋",
                "商品描述：轻量缓震，适合通勤慢跑。",
                Map.of(
                        "productId", "P1001",
                        "skuId", "SKU-P1001-BLK-42",
                        "category", "运动鞋",
                        "brand", "Stride",
                        "price", 499,
                        "stock", 38,
                        "attributes", Map.of("颜色", "黑色", "鞋码", "42")
                )
        );

        ArgumentCaptor<List> childDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(childDocumentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Document> childDocuments = childDocumentsCaptor.getValue();

        assertEquals("P1001", childDocuments.get(0).getMetadata().get("productId"));
        assertEquals("SKU-P1001-BLK-42", childDocuments.get(0).getMetadata().get("skuId"));
        assertEquals("运动鞋", childDocuments.get(0).getMetadata().get("category"));
        assertEquals(Map.of("颜色", "黑色", "鞋码", "42"), childDocuments.get(0).getMetadata().get("attributes"));
        verify(parentDocumentStore).save(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("product-P1001"),
                org.mockito.ArgumentMatchers.eq("云跑 AirLite 缓震跑步鞋"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.argThat(metadata ->
                        "P1001".equals(metadata.get("productId"))
                                && Map.of("颜色", "黑色", "鞋码", "42").equals(metadata.get("attributes")))
        );
    }
}
