package com.example.ragagent.memory;

import com.example.ragagent.config.HierarchicalMemoryProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Component
public class RedisSlidingWindowMemoryStore {

    private static final String SHORT_TERM_KEY_PREFIX = "memory:short:";
    private static final String MESSAGE_LIST_SUFFIX = ":messages";
    private static final String MESSAGE_SEQ_SUFFIX = ":sequence";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HierarchicalMemoryProperties properties;

    public RedisSlidingWindowMemoryStore(StringRedisTemplate redisTemplate,
                                         ObjectMapper objectMapper,
                                         HierarchicalMemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public MemoryWindowSnapshot loadWindow(String conversationId) {
        List<ConversationMemoryEntry> entries = readAllEntries(conversationId);
        return compact(conversationId, entries);
    }

    public MemoryWindowSnapshot append(String conversationId, Message message) {
        if (message == null) {
            return loadWindow(conversationId);
        }
        return append(conversationId, List.of(message));
    }

    public MemoryWindowSnapshot append(String conversationId, List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return loadWindow(conversationId);
        }

        String listKey = listKey(conversationId);
        String sequenceKey = sequenceKey(conversationId);

        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            Long sequence = redisTemplate.opsForValue().increment(sequenceKey);
            if (sequence == null) {
                throw new IllegalStateException("Failed to allocate memory sequence for conversation " + conversationId);
            }
            ConversationMemoryEntry entry = ConversationMemoryEntry.fromMessage(sequence, message);
            redisTemplate.opsForList().rightPush(listKey, serialize(entry));
        }

        refreshTtl(listKey, sequenceKey);
        return loadWindow(conversationId);
    }

    private MemoryWindowSnapshot compact(String conversationId, List<ConversationMemoryEntry> entries) {
        if (entries.isEmpty()) {
            return new MemoryWindowSnapshot(Collections.emptyList(), Collections.emptyList());
        }

        int firstIndexWithinAge = firstIndexWithinAge(entries, Instant.now().minus(properties.getMaxRecentAge()));
        int firstIndexWithinCount = Math.max(0, entries.size() - properties.getMaxRecentMessages());
        int keepFromIndex = Math.max(firstIndexWithinAge, firstIndexWithinCount);

        if (keepFromIndex <= 0) {
            refreshTtl(listKey(conversationId), sequenceKey(conversationId));
            return new MemoryWindowSnapshot(List.copyOf(entries), Collections.emptyList());
        }

        List<ConversationMemoryEntry> evicted = new ArrayList<>(entries.subList(0, keepFromIndex));
        List<ConversationMemoryEntry> retained = new ArrayList<>(entries.subList(keepFromIndex, entries.size()));
        rewriteWindow(conversationId, retained);
        return new MemoryWindowSnapshot(retained, evicted);
    }

    private int firstIndexWithinAge(List<ConversationMemoryEntry> entries, Instant minTimestamp) {
        int index = 0;
        while (index < entries.size() && entries.get(index).timestamp().isBefore(minTimestamp)) {
            index++;
        }
        return index;
    }

    private void rewriteWindow(String conversationId, List<ConversationMemoryEntry> retainedEntries) {
        String listKey = listKey(conversationId);
        redisTemplate.delete(listKey);
        if (!retainedEntries.isEmpty()) {
            List<String> serializedEntries = retainedEntries.stream()
                    .map(this::serialize)
                    .toList();
            redisTemplate.opsForList().rightPushAll(listKey, serializedEntries);
        }
        refreshTtl(listKey, sequenceKey(conversationId));
    }

    private List<ConversationMemoryEntry> readAllEntries(String conversationId) {
        List<String> values = redisTemplate.opsForList().range(listKey(conversationId), 0, -1);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(this::deserialize)
                .toList();
    }

    private void refreshTtl(String listKey, String sequenceKey) {
        Duration ttl = properties.getMaxRecentAge().multipliedBy(2);
        redisTemplate.expire(listKey, ttl);
        redisTemplate.expire(sequenceKey, ttl);
    }

    private String listKey(String conversationId) {
        return SHORT_TERM_KEY_PREFIX + conversationId + MESSAGE_LIST_SUFFIX;
    }

    private String sequenceKey(String conversationId) {
        return SHORT_TERM_KEY_PREFIX + conversationId + MESSAGE_SEQ_SUFFIX;
    }

    private String serialize(ConversationMemoryEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize memory entry", ex);
        }
    }

    private ConversationMemoryEntry deserialize(String value) {
        try {
            return objectMapper.readValue(value, ConversationMemoryEntry.class);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize memory entry", ex);
        }
    }

    public record MemoryWindowSnapshot(
            List<ConversationMemoryEntry> retainedEntries,
            List<ConversationMemoryEntry> evictedEntries
    ) {
    }
}
