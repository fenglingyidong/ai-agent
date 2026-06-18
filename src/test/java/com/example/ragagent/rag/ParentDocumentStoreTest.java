package com.example.ragagent.rag;

import com.example.ragagent.rag.entity.RagParentDocumentEntity;
import com.example.ragagent.rag.impl.ParentDocumentStore;
import com.example.ragagent.rag.mapper.RagParentDocumentMapper;
import com.example.ragagent.observability.RagTracing;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParentDocumentStoreTest {

    private RagParentDocumentMapper mapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private ParentDocumentStore store;
    private RecordingRagTracing tracing;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(RagParentDocumentMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        objectMapper = new ObjectMapper();
        tracing = new RecordingRagTracing();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new ParentDocumentStore(
                mapper,
                redisTemplate,
                objectMapper,
                Duration.ofHours(12),
                Duration.ZERO,
                Duration.ofSeconds(60),
                tracing
        );
    }

    @Test
    void saveShouldWriteParentDocumentToMysqlAndInvalidateParentCache() throws Exception {
        store.save(
                "parent-1",
                " source-1 ",
                " Guide ",
                " Parent text ",
                0,
                "hash-1",
                Map.of("skuId", "SKU-1", "price", 399)
        );

        ArgumentCaptor<RagParentDocumentEntity> entityCaptor = ArgumentCaptor.forClass(RagParentDocumentEntity.class);
        InOrder inOrder = inOrder(mapper, redisTemplate);
        inOrder.verify(mapper).insert(entityCaptor.capture());
        inOrder.verify(redisTemplate).delete("rag:parent:cache:parent-1");
        RagParentDocumentEntity entity = entityCaptor.getValue();

        assertEquals("parent-1", entity.getParentId());
        assertEquals("source-1", entity.getSourceId());
        assertEquals("Guide", entity.getTitle());
        assertEquals("Parent text", entity.getContent());
        assertEquals(0, entity.getParentIndex());
        assertEquals("hash-1", entity.getDocumentHash());
        Map<?, ?> metadata = objectMapper.readValue(entity.getMetadataJson(), Map.class);
        assertEquals("SKU-1", metadata.get("skuId"));
        assertEquals(399, metadata.get("price"));
        verify(valueOperations, never()).set(any(), any());
        verify(redisTemplate, never()).opsForSet();
        verify(redisTemplate, never()).delete(any(java.util.Collection.class));
    }

    @Test
    void saveShouldNotFailWhenRedisInvalidationFails() {
        when(redisTemplate.delete("rag:parent:cache:parent-1"))
                .thenThrow(new RuntimeException("redis delete failed"));

        assertDoesNotThrow(() -> store.save(
                "parent-1",
                "source-1",
                "Guide",
                "Parent text",
                0,
                "hash-1"
        ));

        InOrder inOrder = inOrder(mapper, redisTemplate);
        inOrder.verify(mapper).insert(any(RagParentDocumentEntity.class));
        inOrder.verify(redisTemplate).delete("rag:parent:cache:parent-1");
    }

    @Test
    void loadShouldReturnRedisCachedParentWithoutQueryingMysql() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn("""
                {
                  "parentId":"parent-1",
                  "sourceId":"source-1",
                  "title":"Guide",
                  "text":"Parent text",
                  "parentIndex":0,
                  "documentHash":"hash-1",
                  "extraMetadata":{"skuId":"SKU-1"}
                }
                """);

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        assertEquals("redis", tracing.stringAttribute("rag.parent.load_sources"));
        verify(mapper, never()).selectById("parent-1");
    }

    @Test
    void loadShouldQueryMysqlAndCacheParentWhenRedisMisses() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn(null);
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        assertEquals("mysql", tracing.stringAttribute("rag.parent.load_sources"));
        verify(valueOperations).set(
                eq("rag:parent:cache:parent-1"),
                contains("\"parentId\":\"parent-1\""),
                eq(Duration.ofHours(12))
        );
    }

    @Test
    void loadShouldFallBackToMysqlWhenRedisGetFails() {
        when(valueOperations.get("rag:parent:cache:parent-1"))
                .thenThrow(new RuntimeException("redis get failed"));
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        verify(mapper).selectById("parent-1");
    }

    @Test
    void loadShouldAccumulateDistinctLoadSourcesWithinCurrentSpan() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn("""
                {
                  "parentId":"parent-1",
                  "sourceId":"source-1",
                  "title":"Guide",
                  "text":"Parent text",
                  "parentIndex":0,
                  "documentHash":"hash-1",
                  "extraMetadata":{"skuId":"SKU-1"}
                }
                """);
        when(valueOperations.get("rag:parent:cache:parent-2")).thenReturn(null);
        RagParentDocumentEntity secondParent = parentEntity();
        secondParent.setParentId("parent-2");
        when(mapper.selectById("parent-2")).thenReturn(secondParent);

        assertTrue(store.load("parent-1").isPresent());
        assertTrue(store.load("parent-2").isPresent());

        assertEquals("redis,mysql", tracing.stringAttribute("rag.parent.load_sources"));
    }

    @Test
    void loadShouldReturnMysqlResultWhenRedisCacheSetFails() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn(null);
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());
        doThrow(new RuntimeException("redis set failed")).when(valueOperations).set(
                eq("rag:parent:cache:parent-1"),
                anyString(),
                eq(Duration.ofHours(12))
        );

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
    }

    @Test
    void loadShouldQueryMysqlOnceForConcurrentMissesOfSameParentId() throws Exception {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn(null);
        RagParentDocumentEntity entity = parentEntity();
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        when(mapper.selectById("parent-1")).thenAnswer(invocation -> {
            firstQueryStarted.countDown();
            assertTrue(releaseQuery.await(2, TimeUnit.SECONDS));
            return entity;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try {
            Future<?> first = executor.submit(() -> assertTrue(store.load("parent-1").isPresent()));
            assertTrue(firstQueryStarted.await(2, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                assertTrue(store.load("parent-1").isPresent());
            });
            awaitThreadWaiting(secondThread);
            releaseQuery.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
        finally {
            executor.shutdownNow();
        }

        verify(mapper).selectById("parent-1");
    }

    @Test
    void loadShouldNotCacheParentWhenSourceDeleteHappensAfterMysqlRead() throws Exception {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn(null);
        when(mapper.selectParentIdsBySourceId("source-1")).thenReturn(List.of("parent-1"));
        CountDownLatch oldEntityRead = new CountDownLatch(1);
        CountDownLatch allowLoadToContinue = new CountDownLatch(1);
        when(mapper.selectById("parent-1")).thenAnswer(invocation -> {
            RagParentDocumentEntity oldEntity = parentEntity();
            oldEntity.setContent("Old parent text");
            oldEntityRead.countDown();
            assertTrue(allowLoadToContinue.await(2, TimeUnit.SECONDS));
            return oldEntity;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Document> load = executor.submit(() -> store.load("parent-1").orElseThrow());
            assertTrue(oldEntityRead.await(2, TimeUnit.SECONDS));

            store.deleteBySourceId("source-1");

            allowLoadToContinue.countDown();
            Document document = load.get(2, TimeUnit.SECONDS);

            assertEquals("Old parent text", document.getText());
        }
        finally {
            executor.shutdownNow();
        }

        verify(valueOperations, never()).set(
                eq("rag:parent:cache:parent-1"),
                contains("Old parent text"),
                any(Duration.class)
        );
        verify(redisTemplate, times(2)).delete(List.of("rag:parent:cache:parent-1"));
    }

    @Test
    void deleteBySourceIdShouldWaitWhenOldLoadIsWritingCacheAfterVersionCheck() throws Exception {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn(null);
        RagParentDocumentEntity oldEntity = parentEntity();
        oldEntity.setContent("Old parent text");
        when(mapper.selectById("parent-1")).thenReturn(oldEntity);
        when(mapper.selectParentIdsBySourceId("source-1")).thenReturn(List.of("parent-1"));
        CountDownLatch cacheSetEntered = new CountDownLatch(1);
        CountDownLatch allowCacheSetToReturn = new CountDownLatch(1);
        doAnswer(invocation -> {
            cacheSetEntered.countDown();
            assertTrue(allowCacheSetToReturn.await(2, TimeUnit.SECONDS));
            return null;
        }).when(valueOperations).set(
                eq("rag:parent:cache:parent-1"),
                contains("Old parent text"),
                any(Duration.class)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch deleteFinished = new CountDownLatch(1);
        try {
            Future<Document> load = executor.submit(() -> store.load("parent-1").orElseThrow());
            assertTrue(cacheSetEntered.await(2, TimeUnit.SECONDS));

            Future<?> delete = executor.submit(() -> {
                deleteStarted.countDown();
                store.deleteBySourceId("source-1");
                deleteFinished.countDown();
            });

            assertTrue(deleteStarted.await(2, TimeUnit.SECONDS));
            assertFalse(deleteFinished.await(100, TimeUnit.MILLISECONDS));
            verify(mapper, never()).selectParentIdsBySourceId("source-1");

            allowCacheSetToReturn.countDown();
            Document document = load.get(2, TimeUnit.SECONDS);
            delete.get(2, TimeUnit.SECONDS);

            assertEquals("Old parent text", document.getText());
        }
        finally {
            allowCacheSetToReturn.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void loadShouldUnwrapSingleFlightRuntimeExceptionForWaitingThread() throws Exception {
        RuntimeException expected = new IllegalStateException("mysql down");
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn(null);
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        when(mapper.selectById("parent-1")).thenAnswer(invocation -> {
            firstQueryStarted.countDown();
            assertTrue(releaseQuery.await(2, TimeUnit.SECONDS));
            throw expected;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try {
            Future<Throwable> first = executor.submit(() -> catchLoadError("parent-1"));
            assertTrue(firstQueryStarted.await(2, TimeUnit.SECONDS));
            Future<Throwable> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                return catchLoadError("parent-1");
            });
            awaitThreadWaiting(secondThread);
            releaseQuery.countDown();

            assertSame(expected, first.get(2, TimeUnit.SECONDS));
            assertSame(expected, second.get(2, TimeUnit.SECONDS));
        }
        finally {
            executor.shutdownNow();
        }

        verify(mapper).selectById("parent-1");
    }

    @Test
    void loadShouldEvictInvalidRedisCacheAndRecoverFromMysql() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn("not-json");
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        verify(redisTemplate).delete("rag:parent:cache:parent-1");
        verify(valueOperations).set(
                eq("rag:parent:cache:parent-1"),
                contains("\"parentId\":\"parent-1\""),
                eq(Duration.ofHours(12))
        );
    }

    @Test
    void loadShouldRecoverFromMysqlWhenInvalidCacheEvictionFails() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn("not-json");
        when(redisTemplate.delete("rag:parent:cache:parent-1"))
                .thenThrow(new RuntimeException("redis delete failed"));
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        verify(redisTemplate).delete("rag:parent:cache:parent-1");
        verify(mapper).selectById("parent-1");
    }

    @Test
    void loadShouldEvictBlankRedisCacheAndRecoverFromMysql() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn("   ");
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        verify(redisTemplate).delete("rag:parent:cache:parent-1");
        verify(mapper).selectById("parent-1");
        verify(valueOperations).set(
                eq("rag:parent:cache:parent-1"),
                contains("\"parentId\":\"parent-1\""),
                eq(Duration.ofHours(12))
        );
    }

    @Test
    void loadShouldEvictIncompleteRedisCacheAndRecoverFromMysql() {
        when(valueOperations.get("rag:parent:cache:parent-1"))
                .thenReturn("{\"parentId\":\"parent-1\",\"sourceId\":\"source-1\",\"text\":\"Parent text\"}");
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        verify(redisTemplate).delete("rag:parent:cache:parent-1");
        verify(mapper).selectById("parent-1");
        verify(valueOperations).set(
                eq("rag:parent:cache:parent-1"),
                contains("\"parentId\":\"parent-1\""),
                eq(Duration.ofHours(12))
        );
    }

    @Test
    void loadShouldEvictParentCacheWithMismatchedParentIdAndRecoverFromMysql() {
        when(valueOperations.get("rag:parent:cache:parent-1")).thenReturn("""
                {
                  "parentId":"other-parent",
                  "sourceId":"source-1",
                  "title":"Guide",
                  "text":"Parent text",
                  "parentIndex":0,
                  "documentHash":"hash-1",
                  "extraMetadata":{"skuId":"SKU-1"}
                }
                """);
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        verify(redisTemplate).delete("rag:parent:cache:parent-1");
        verify(mapper).selectById("parent-1");
        verify(valueOperations).set(
                eq("rag:parent:cache:parent-1"),
                contains("\"parentId\":\"parent-1\""),
                eq(Duration.ofHours(12))
        );
    }

    @Test
    void loadShouldCacheShortMissingMarkerWhenMysqlMisses() {
        when(valueOperations.get("rag:parent:cache:missing-parent")).thenReturn(null);
        when(mapper.selectById("missing-parent")).thenReturn(null);

        assertTrue(store.load("missing-parent").isEmpty());

        verify(valueOperations).set(
                eq("rag:parent:cache:missing-parent"),
                eq("{\"missing\":true}"),
                eq(Duration.ofSeconds(60))
        );
    }

    @Test
    void loadShouldReturnEmptyWhenMissingMarkerIsCached() {
        when(valueOperations.get("rag:parent:cache:missing-parent")).thenReturn("{\"missing\":true}");

        assertTrue(store.load("missing-parent").isEmpty());

        verify(valueOperations).get("rag:parent:cache:missing-parent");
        verify(mapper, never()).selectById("missing-parent");
    }

    @Test
    void loadShouldEvictMissingMarkerWithExtraFieldsAndRecoverFromMysql() {
        when(valueOperations.get("rag:parent:cache:parent-1"))
                .thenReturn("{\"missing\":true,\"parentId\":\"parent-1\"}");
        when(mapper.selectById("parent-1")).thenReturn(parentEntity());

        Document document = store.load("parent-1").orElseThrow();

        assertEquals("Parent text", document.getText());
        assertEquals("SKU-1", document.getMetadata().get("skuId"));
        assertParentMetadata(document);
        verify(redisTemplate).delete("rag:parent:cache:parent-1");
        verify(mapper).selectById("parent-1");
    }

    @Test
    void deleteBySourceIdShouldDoubleDeleteRedisCachesAroundMysqlRows() {
        when(mapper.selectParentIdsBySourceId("source-1")).thenReturn(List.of("parent-1", "parent-2"));

        store.deleteBySourceId(" source-1 ");

        InOrder inOrder = inOrder(mapper, redisTemplate);
        inOrder.verify(mapper).selectParentIdsBySourceId("source-1");
        inOrder.verify(redisTemplate).delete(List.of("rag:parent:cache:parent-1", "rag:parent:cache:parent-2"));
        inOrder.verify(mapper).deleteBySourceId("source-1");
        inOrder.verify(redisTemplate).delete(List.of("rag:parent:cache:parent-1", "rag:parent:cache:parent-2"));
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    void deleteBySourceIdShouldUseDefaultSourceWhenBlank() {
        when(mapper.selectParentIdsBySourceId("default-source")).thenReturn(List.of("parent-1"));

        store.deleteBySourceId(" ");

        InOrder inOrder = inOrder(mapper, redisTemplate);
        inOrder.verify(mapper).selectParentIdsBySourceId("default-source");
        inOrder.verify(redisTemplate).delete(List.of("rag:parent:cache:parent-1"));
        inOrder.verify(mapper).deleteBySourceId("default-source");
        inOrder.verify(redisTemplate).delete(List.of("rag:parent:cache:parent-1"));
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    void deleteBySourceIdShouldTrimParentIdsAndIgnoreBlankParentIds() {
        when(mapper.selectParentIdsBySourceId("source-1"))
                .thenReturn(Arrays.asList(" parent-1 ", "", "   ", null));

        store.deleteBySourceId("source-1");

        InOrder inOrder = inOrder(mapper, redisTemplate);
        inOrder.verify(mapper).selectParentIdsBySourceId("source-1");
        inOrder.verify(redisTemplate).delete(List.of("rag:parent:cache:parent-1"));
        inOrder.verify(mapper).deleteBySourceId("source-1");
        inOrder.verify(redisTemplate).delete(List.of("rag:parent:cache:parent-1"));
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    void deleteBySourceIdShouldDeleteMysqlRowsWhenRedisDeleteFails() {
        List<String> cacheKeys = List.of("rag:parent:cache:parent-1");
        when(mapper.selectParentIdsBySourceId("source-1")).thenReturn(List.of("parent-1"));
        when(redisTemplate.delete(cacheKeys)).thenThrow(new RuntimeException("redis delete failed"));

        assertDoesNotThrow(() -> store.deleteBySourceId("source-1"));

        InOrder inOrder = inOrder(mapper, redisTemplate);
        inOrder.verify(mapper).selectParentIdsBySourceId("source-1");
        inOrder.verify(redisTemplate).delete(cacheKeys);
        inOrder.verify(mapper).deleteBySourceId("source-1");
        inOrder.verify(redisTemplate).delete(cacheKeys);
        verify(redisTemplate, times(2)).delete(cacheKeys);
    }

    @Test
    void constructorShouldRejectNonPositiveCacheTtls() {
        assertThrows(IllegalArgumentException.class, () -> newStore(Duration.ZERO, Duration.ZERO, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> newStore(Duration.ofHours(12), Duration.ZERO, Duration.ZERO));
    }

    @Test
    void constructorShouldRejectNegativeCacheTtlJitter() {
        assertThrows(IllegalArgumentException.class,
                () -> newStore(Duration.ofHours(12), Duration.ofMillis(-1), Duration.ofSeconds(60)));
    }

    @Test
    void constructorShouldRejectDurationMillisOverflow() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> newStore(Duration.ofSeconds(Long.MAX_VALUE), Duration.ZERO, Duration.ofSeconds(60)));

        assertTrue(exception.getMessage().contains("cacheTtl"));
    }

    private ParentDocumentStore newStore(Duration cacheTtl, Duration cacheTtlJitter, Duration missingCacheTtl) {
        return new ParentDocumentStore(
                mapper,
                redisTemplate,
                objectMapper,
                cacheTtl,
                cacheTtlJitter,
                missingCacheTtl,
                tracing
        );
    }

    private RagParentDocumentEntity parentEntity() {
        RagParentDocumentEntity entity = new RagParentDocumentEntity();
        entity.setParentId("parent-1");
        entity.setSourceId("source-1");
        entity.setTitle("Guide");
        entity.setContent("Parent text");
        entity.setParentIndex(0);
        entity.setDocumentHash("hash-1");
        entity.setMetadataJson("{\"skuId\":\"SKU-1\"}");
        return entity;
    }

    private Throwable catchLoadError(String parentId) {
        try {
            store.load(parentId);
            return null;
        }
        catch (Throwable ex) {
            return ex;
        }
    }

    private void awaitThreadWaiting(AtomicReference<Thread> threadReference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Thread thread = threadReference.get();
            if (thread != null) {
                Thread.State state = thread.getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    return;
                }
            }
            Thread.sleep(10);
        }

        Thread thread = threadReference.get();
        fail("Second load thread did not enter waiting state. Current state: "
                + (thread == null ? "not started" : thread.getState()));
    }

    private void assertParentMetadata(Document document) {
        assertEquals("parent-1", document.getMetadata().get("parentId"));
        assertEquals("source-1", document.getMetadata().get("sourceId"));
        assertEquals("Guide", document.getMetadata().get("title"));
        assertEquals(0, document.getMetadata().get("parentIndex"));
        assertEquals("hash-1", document.getMetadata().get("documentHash"));
    }

    private static final class RecordingRagTracing extends RagTracing {

        private final Map<String, String> stringAttributes = new java.util.LinkedHashMap<>();

        @Override
        public Span currentSpan() {
            return Span.getInvalid();
        }

        @Override
        public void setAttribute(Span span, String key, String value) {
            stringAttributes.put(key, value);
        }

        @Override
        public void appendDistinctCsvAttribute(Span span, String key, String value) {
            String currentValue = stringAttributes.get(key);
            if (currentValue == null || currentValue.isBlank()) {
                stringAttributes.put(key, value);
                return;
            }
            List<String> values = Arrays.asList(currentValue.split(","));
            if (!values.contains(value)) {
                stringAttributes.put(key, currentValue + "," + value);
            }
        }

        private String stringAttribute(String key) {
            return stringAttributes.get(key);
        }
    }
}
