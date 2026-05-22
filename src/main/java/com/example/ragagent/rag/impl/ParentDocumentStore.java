package com.example.ragagent.rag.impl;

import com.example.ragagent.rag.RagDocumentConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class ParentDocumentStore {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 保存单个父分块，并维护 sourceId 到 parentId 的映射关系。
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
        String normalizedSourceId = normalizeSourceId(sourceId);
        StoredParentDocument parentDocument = new StoredParentDocument(
                parentId,
                normalizedSourceId,
                title == null ? "" : title.trim(),
                text == null ? "" : text.trim(),
                parentIndex,
                documentHash == null ? "" : documentHash,
                sanitizeMetadata(extraMetadata)
        );
        redisTemplate.opsForValue().set(parentKey(parentId), serialize(parentDocument));
        redisTemplate.opsForSet().add(sourceParentSetKey(normalizedSourceId), parentId);
    }

    /**
     * 按 parentId 加载父分块，并恢复为带元数据的 Spring AI 文档。
     */
    public Optional<Document> load(String parentId) {
        String serializedParent = redisTemplate.opsForValue().get(parentKey(parentId));
        if (!StringUtils.hasText(serializedParent)) {
            return Optional.empty();
        }

        StoredParentDocument parentDocument = deserialize(serializedParent);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagDocumentConstants.METADATA_PARENT_ID, parentDocument.parentId());
        metadata.put(RagDocumentConstants.METADATA_SOURCE_ID, parentDocument.sourceId());
        metadata.put(RagDocumentConstants.METADATA_TITLE, parentDocument.title());
        metadata.put(RagDocumentConstants.METADATA_PARENT_INDEX, parentDocument.parentIndex());
        metadata.put(RagDocumentConstants.METADATA_DOCUMENT_HASH, parentDocument.documentHash());
        metadata.putAll(parentDocument.extraMetadata());

        return Optional.of(Document.builder()
                .id(parentDocument.parentId())
                .text(parentDocument.text())
                .metadata(metadata)
                .build());
    }

    /**
     * 按 sourceId 删除旧的父分块数据，配合重新导入时覆盖历史索引。
     */
    public void deleteBySourceId(String sourceId) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        Set<String> parentIds = redisTemplate.opsForSet().members(sourceParentSetKey(normalizedSourceId));
        if (parentIds != null && !parentIds.isEmpty()) {
            Set<String> parentKeys = new LinkedHashSet<>();
            for (String parentId : parentIds) {
                if (StringUtils.hasText(parentId)) {
                    parentKeys.add(parentKey(parentId));
                }
            }
            if (!parentKeys.isEmpty()) {
                redisTemplate.delete(parentKeys);
            }
        }
        redisTemplate.delete(sourceParentSetKey(normalizedSourceId));
    }

    /**
     * 规范化 sourceId，确保 Redis 键和元数据中的来源标识稳定一致。
     */
    private String normalizeSourceId(String sourceId) {
        return StringUtils.hasText(sourceId) ? sourceId.trim() : RagDocumentConstants.DEFAULT_SOURCE_ID;
    }

    /**
     * 构建单个父分块对应的 Redis 键。
     */
    private String parentKey(String parentId) {
        return RagDocumentConstants.PARENT_KEY_PREFIX + parentId;
    }

    /**
     * 构建 sourceId 对应的 parentId 集合键。
     */
    private String sourceParentSetKey(String sourceId) {
        return RagDocumentConstants.SOURCE_PARENT_SET_KEY_PREFIX + sourceId;
    }

    /**
     * 将父分块序列化为 JSON 后再写入 Redis。
     */
    private String serialize(StoredParentDocument parentDocument) {
        try {
            return objectMapper.writeValueAsString(parentDocument);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize parent RAG document", ex);
        }
    }

    /**
     * 从 Redis 读取后，将 JSON 反序列化为父分块对象。
     */
    private StoredParentDocument deserialize(String value) {
        try {
            return objectMapper.readValue(value, StoredParentDocument.class);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize parent RAG document", ex);
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
}
