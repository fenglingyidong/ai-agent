package com.example.ragagent.rag.impl;

import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.rag.RagDocumentConstants;
import com.example.ragagent.rag.entity.RagParentDocumentEntity;
import com.example.ragagent.rag.mapper.RagParentDocumentMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 父文档持久化和缓存组件，负责 MySQL 存储、Redis 缓存、空值缓存和单飞加载。
 */
@Component
public class ParentDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(ParentDocumentStore.class);

    private final RagParentDocumentMapper parentDocumentMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RagTracing tracing;
    private final Duration cacheTtl;
    private final Duration cacheTtlJitter;
    private final long cacheTtlJitterMillis;
    private final Duration missingCacheTtl;
    private final ConcurrentMap<String, CompletableFuture<Optional<Document>>> inFlightLoads = new ConcurrentHashMap<>();
    private final AtomicLong cacheMutationVersion = new AtomicLong();
    private final Object cacheMutationMonitor = new Object();

    public ParentDocumentStore(RagParentDocumentMapper parentDocumentMapper,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               @Value("${app.rag.parent-cache.ttl:12h}") Duration cacheTtl,
                               @Value("${app.rag.parent-cache.ttl-jitter:6h}") Duration cacheTtlJitter,
                               @Value("${app.rag.parent-cache.missing-ttl:60s}") Duration missingCacheTtl) {
        this(parentDocumentMapper, redisTemplate, objectMapper, cacheTtl, cacheTtlJitter, missingCacheTtl, new RagTracing());
    }

    @Autowired
    public ParentDocumentStore(RagParentDocumentMapper parentDocumentMapper,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               @Value("${app.rag.parent-cache.ttl:12h}") Duration cacheTtl,
                               @Value("${app.rag.parent-cache.ttl-jitter:6h}") Duration cacheTtlJitter,
                               @Value("${app.rag.parent-cache.missing-ttl:60s}") Duration missingCacheTtl,
                               RagTracing tracing) {
        this.parentDocumentMapper = parentDocumentMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tracing = tracing == null ? new RagTracing() : tracing;
        this.cacheTtl = requirePositiveDuration(cacheTtl, "cacheTtl");
        this.cacheTtlJitter = requireNonNegativeDuration(cacheTtlJitter, "cacheTtlJitter");
        this.cacheTtlJitterMillis = toMillisOrThrow(this.cacheTtlJitter, "cacheTtlJitter");
        this.missingCacheTtl = requirePositiveDuration(missingCacheTtl, "missingCacheTtl");
    }

    /**
     * 保存单个父分块。
     */
    public void save(String parentId,
                     String sourceId,
                     String title,
                     String text,
                     int parentIndex,
                     String documentHash) {
        save(parentId, sourceId, title, text, parentIndex, documentHash, Map.of());
    }

    /**
     * 保存单个父分块，并附加商品域元数据。
     */
    public void save(String parentId,
                     String sourceId,
                     String title,
                     String text,
                     int parentIndex,
                     String documentHash,
                     Map<String, Object> extraMetadata) {
        StoredParentDocument parentDocument = new StoredParentDocument(
                normalizeParentId(parentId),
                normalizeSourceId(sourceId),
                title == null ? "" : title.trim(),
                text == null ? "" : text.trim(),
                parentIndex,
                documentHash == null ? "" : documentHash,
                sanitizeMetadata(extraMetadata)
        );
        markCacheMutation();
        parentDocumentMapper.insert(toEntity(parentDocument));
        markCacheMutation();
        safeDeleteCache(parentCacheKey(parentDocument.parentId()));
    }

    /**
     * 按 parentId 加载父分块，并恢复为带元数据的 Spring AI 文档。
     */
    public Optional<Document> load(String parentId) {
        String normalizedParentId = normalizeParentId(parentId);
        if (!StringUtils.hasText(normalizedParentId)) {
            return Optional.empty();
        }

        CacheLookup cacheLookup = loadFromCache(normalizedParentId);
        if (cacheLookup.hit()) {
            recordLoadSource("redis");
            return cacheLookup.document();
        }

        CompletableFuture<Optional<Document>> newFuture = new CompletableFuture<>();
        CompletableFuture<Optional<Document>> existingFuture = inFlightLoads.putIfAbsent(normalizedParentId, newFuture);
        if (existingFuture != null) {
            return joinUnwrappingCompletionException(existingFuture);
        }
        try {
            Optional<Document> loaded = loadFromMysqlAndCache(normalizedParentId);
            recordLoadSource("mysql");
            newFuture.complete(loaded);
            return loaded;
        }
        catch (RuntimeException | Error ex) {
            newFuture.completeExceptionally(ex);
            throw ex;
        }
        finally {
            inFlightLoads.remove(normalizedParentId, newFuture);
        }
    }

    /**
     * 按 sourceId 删除旧的父分块数据，配合重新导入时覆盖历史索引。
     */
    public void deleteBySourceId(String sourceId) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        markCacheMutation();
        List<String> parentIds = parentDocumentMapper.selectParentIdsBySourceId(normalizedSourceId);
        List<String> cacheKeys = parentIds == null ? List.of() : parentIds.stream()
                .filter(StringUtils::hasText)
                .map(parentId -> parentCacheKey(parentId.trim()))
                .toList();
        if (!cacheKeys.isEmpty()) {
            safeDeleteCache(cacheKeys);
        }
        parentDocumentMapper.deleteBySourceId(normalizedSourceId);
        markCacheMutation();
        if (!cacheKeys.isEmpty()) {
            safeDeleteCache(cacheKeys);
        }
    }

    private CacheLookup loadFromCache(String parentId) {
        String cacheKey = parentCacheKey(parentId);
        String cachedValue;
        try {
            cachedValue = redisTemplate.opsForValue().get(cacheKey);
        }
        catch (RuntimeException ex) {
            log.warn("Failed to read parent document cache for parentId={}", parentId, ex);
            return new CacheLookup(false, Optional.empty());
        }
        if (cachedValue == null) {
            return new CacheLookup(false, Optional.empty());
        }
        if (!StringUtils.hasText(cachedValue)) {
            safeDeleteCache(cacheKey);
            return new CacheLookup(false, Optional.empty());
        }
        try {
            JsonNode root = objectMapper.readTree(cachedValue);
            if (isMissingMarker(root)) {
                return new CacheLookup(true, Optional.empty());
            }
            if (!isValidParentCacheNode(root, parentId)) {
                safeDeleteCache(cacheKey);
                return new CacheLookup(false, Optional.empty());
            }
            StoredParentDocument parentDocument = objectMapper.treeToValue(root, StoredParentDocument.class);
            return new CacheLookup(true, Optional.of(toDocument(parentDocument)));
        }
        catch (JsonProcessingException | IllegalArgumentException ex) {
            safeDeleteCache(cacheKey);
            return new CacheLookup(false, Optional.empty());
        }
    }

    private boolean isMissingMarker(JsonNode root) {
        JsonNode missing = root.get("missing");
        return root.isObject()
                && root.size() == 1
                && missing != null
                && missing.isBoolean()
                && missing.asBoolean();
    }

    private boolean isValidParentCacheNode(JsonNode root, String parentId) {
        return root.isObject()
                && hasTextField(root, "parentId")
                && root.path("parentId").asText().equals(parentId)
                && hasTextField(root, "sourceId")
                && hasTextFieldAllowingEmpty(root, "title")
                && hasTextField(root, "text")
                && root.has("parentIndex")
                && root.get("parentIndex").isNumber()
                && hasTextFieldAllowingEmpty(root, "documentHash")
                && root.has("extraMetadata")
                && root.get("extraMetadata").isObject();
    }

    private boolean hasTextField(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        return field != null && field.isTextual() && StringUtils.hasText(field.asText());
    }

    private boolean hasTextFieldAllowingEmpty(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        return field != null && field.isTextual();
    }

    private Optional<Document> loadFromMysqlAndCache(String parentId) {
        long versionBeforeLoad = cacheMutationVersion.get();
        RagParentDocumentEntity entity = parentDocumentMapper.selectById(parentId);
        if (entity == null) {
            cacheMissingIfVersionUnchanged(parentId, versionBeforeLoad);
            return Optional.empty();
        }

        StoredParentDocument parentDocument = fromEntity(entity);
        cacheParentIfVersionUnchanged(parentDocument, versionBeforeLoad);
        return Optional.of(toDocument(parentDocument));
    }

    private void recordLoadSource(String source) {
        Span span = tracing.currentSpan();
        tracing.appendDistinctCsvAttribute(span, "rag.parent.load_sources", source);
    }

    private long markCacheMutation() {
        synchronized (cacheMutationMonitor) {
            return cacheMutationVersion.incrementAndGet();
        }
    }

    private void cacheParentIfVersionUnchanged(StoredParentDocument parentDocument, long expectedVersion) {
        synchronized (cacheMutationMonitor) {
            if (cacheMutationVersion.get() == expectedVersion) {
                cacheParent(parentDocument);
            }
        }
    }

    private void cacheMissingIfVersionUnchanged(String parentId, long expectedVersion) {
        synchronized (cacheMutationMonitor) {
            if (cacheMutationVersion.get() == expectedVersion) {
                cacheMissing(parentId);
            }
        }
    }

    private void cacheParent(StoredParentDocument parentDocument) {
        String cacheKey = parentCacheKey(parentDocument.parentId());
        String cachedValue = serialize(parentDocument);
        Duration ttl = nextCacheTtl();
        try {
            redisTemplate.opsForValue().set(cacheKey, cachedValue, ttl);
        }
        catch (RuntimeException ex) {
            log.warn("Failed to write parent document cache for parentId={}", parentDocument.parentId(), ex);
        }
    }

    private void cacheMissing(String parentId) {
        String cacheKey = parentCacheKey(parentId);
        try {
            redisTemplate.opsForValue().set(cacheKey, "{\"missing\":true}", missingCacheTtl);
        }
        catch (RuntimeException ex) {
            log.warn("Failed to write missing parent document cache for parentId={}", parentId, ex);
        }
    }

    private String parentCacheKey(String parentId) {
        return RagDocumentConstants.PARENT_CACHE_KEY_PREFIX + parentId;
    }

    private Duration nextCacheTtl() {
        if (cacheTtlJitter.isZero()) {
            return cacheTtl;
        }
        long jitterMillis = cacheTtlJitterMillis == Long.MAX_VALUE
                ? ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)
                : ThreadLocalRandom.current().nextLong(cacheTtlJitterMillis + 1);
        return cacheTtl.plusMillis(jitterMillis);
    }

    private static Duration requirePositiveDuration(Duration duration, String name) {
        if (duration == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (duration.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        toMillisOrThrow(duration, name);
        return duration;
    }

    private static Duration requireNonNegativeDuration(Duration duration, String name) {
        if (duration == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (duration.compareTo(Duration.ZERO) < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        toMillisOrThrow(duration, name);
        return duration;
    }

    private static long toMillisOrThrow(Duration duration, String name) {
        try {
            return duration.toMillis();
        }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " is too large to convert to milliseconds", ex);
        }
    }

    private StoredParentDocument fromEntity(RagParentDocumentEntity entity) {
        return new StoredParentDocument(
                entity.getParentId(),
                entity.getSourceId(),
                entity.getTitle(),
                entity.getContent(),
                entity.getParentIndex(),
                entity.getDocumentHash(),
                deserializeMetadata(entity.getMetadataJson())
        );
    }

    private Document toDocument(StoredParentDocument parentDocument) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagDocumentConstants.METADATA_PARENT_ID, parentDocument.parentId());
        metadata.put(RagDocumentConstants.METADATA_SOURCE_ID, parentDocument.sourceId());
        metadata.put(RagDocumentConstants.METADATA_TITLE, parentDocument.title());
        metadata.put(RagDocumentConstants.METADATA_PARENT_INDEX, parentDocument.parentIndex());
        metadata.put(RagDocumentConstants.METADATA_DOCUMENT_HASH, parentDocument.documentHash());
        metadata.putAll(parentDocument.extraMetadata());

        return Document.builder()
                .id(parentDocument.parentId())
                .text(parentDocument.text())
                .metadata(metadata)
                .build();
    }

    private RagParentDocumentEntity toEntity(StoredParentDocument parentDocument) {
        RagParentDocumentEntity entity = new RagParentDocumentEntity();
        entity.setParentId(parentDocument.parentId());
        entity.setSourceId(parentDocument.sourceId());
        entity.setTitle(parentDocument.title());
        entity.setContent(parentDocument.text());
        entity.setParentIndex(parentDocument.parentIndex());
        entity.setDocumentHash(parentDocument.documentHash());
        entity.setMetadataJson(serializeMetadata(parentDocument.extraMetadata()));
        return entity;
    }

    private String normalizeSourceId(String sourceId) {
        return StringUtils.hasText(sourceId) ? sourceId.trim() : RagDocumentConstants.DEFAULT_SOURCE_ID;
    }

    private String normalizeParentId(String parentId) {
        return parentId == null ? "" : parentId.trim();
    }

    private Optional<Document> joinUnwrappingCompletionException(CompletableFuture<Optional<Document>> future) {
        try {
            return future.join();
        }
        catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }

    private void safeDeleteCache(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        }
        catch (RuntimeException ex) {
            log.warn("Failed to delete parent document cache key={}", cacheKey, ex);
        }
    }

    private void safeDeleteCache(List<String> cacheKeys) {
        try {
            redisTemplate.delete(cacheKeys);
        }
        catch (RuntimeException ex) {
            log.warn("Failed to delete parent document cache keys={}", cacheKeys, ex);
        }
    }

    private String serialize(StoredParentDocument parentDocument) {
        try {
            return objectMapper.writeValueAsString(parentDocument);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize parent RAG document", ex);
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize parent RAG document metadata", ex);
        }
    }

    private Map<String, Object> deserializeMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize parent RAG document metadata", ex);
        }
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                sanitized.put(key.trim(), value);
            }
        });
        return Map.copyOf(sanitized);
    }

    private record StoredParentDocument(
            String parentId,
            String sourceId,
            String title,
            String text,
            int parentIndex,
            String documentHash,
            Map<String, Object> extraMetadata
    ) {

        /**
         * 在父分块入库前兜底规整可空字段。
         */
        private StoredParentDocument {
            parentId = Objects.requireNonNull(parentId, "parentId");
            sourceId = sourceId == null ? "" : sourceId;
            title = title == null ? "" : title;
            text = text == null ? "" : text;
            documentHash = documentHash == null ? "" : documentHash;
            extraMetadata = extraMetadata == null ? Map.of() : Map.copyOf(extraMetadata);
        }
    }

    private record CacheLookup(boolean hit, Optional<Document> document) {
    }
}
