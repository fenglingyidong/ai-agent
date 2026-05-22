package com.example.ragagent.memory;

import com.example.ragagent.config.HierarchicalMemoryProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String SHORT_TERM_KEY_PREFIX = "memory:short:";
    private static final String MESSAGE_LIST_SUFFIX = ":messages";
    private static final String MESSAGE_SEQ_SUFFIX = ":sequence";
    private static final String DEFAULT_USER_ID = "anonymous";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HierarchicalMemoryProperties properties;
    private final LongTermMemoryService longTermMemoryService;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     HierarchicalMemoryProperties properties,
                                     LongTermMemoryService longTermMemoryService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.longTermMemoryService = longTermMemoryService;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(SHORT_TERM_KEY_PREFIX + "*" + MESSAGE_LIST_SUFFIX);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(this::conversationIdFromListKey)
                .filter(StringUtils::hasText)
                .sorted()
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        MemoryWindowSnapshot snapshot = compactByAge(conversationId, readAllEntries(conversationId));
        summarizeEvicted(conversationId, snapshot.evictedEntries());
        return snapshot.retainedEntries().stream()
                .map(ConversationMemoryEntry::toMessage)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        MemoryWindowSnapshot ageSnapshot = compactByAge(conversationId, readAllEntries(conversationId));
        summarizeEvicted(conversationId, ageSnapshot.evictedEntries());
        List<ConversationMemoryEntry> currentEntries = ageSnapshot.retainedEntries();
        MergeResult mergeResult = mergeSavedMessages(conversationId, currentEntries, messages);
        rewriteWindow(conversationId, mergeResult.retainedEntries());
        summarizeEvicted(conversationId, mergeResult.evictedEntries());
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        redisTemplate.delete(listKey(conversationId));
        redisTemplate.delete(sequenceKey(conversationId));
    }

    private MemoryWindowSnapshot compactByAge(String conversationId, List<ConversationMemoryEntry> entries) {
        if (entries.isEmpty()) {
            return new MemoryWindowSnapshot(List.of(), List.of());
        }

        Instant minTimestamp = Instant.now().minus(properties.getMaxRecentAge());
        int keepFromIndex = 0;
        while (keepFromIndex < entries.size() && entries.get(keepFromIndex).timestamp().isBefore(minTimestamp)) {
            keepFromIndex++;
        }

        if (keepFromIndex <= 0) {
            refreshTtl(conversationId);
            return new MemoryWindowSnapshot(List.copyOf(entries), List.of());
        }

        List<ConversationMemoryEntry> evicted = new ArrayList<>(entries.subList(0, keepFromIndex));
        List<ConversationMemoryEntry> retained = new ArrayList<>(entries.subList(keepFromIndex, entries.size()));
        rewriteWindow(conversationId, retained);
        return new MemoryWindowSnapshot(retained, evicted);
    }

    private MergeResult mergeSavedMessages(String conversationId,
                                           List<ConversationMemoryEntry> currentEntries,
                                           List<Message> savedMessages) {
        List<ConversationMemoryEntry> retained = new ArrayList<>();
        Set<Integer> matchedIndexes = new HashSet<>();

        for (Message message : savedMessages) {
            int matchedIndex = findMatchingEntryIndex(currentEntries, matchedIndexes, message);
            if (matchedIndex >= 0) {
                matchedIndexes.add(matchedIndex);
                retained.add(currentEntries.get(matchedIndex));
            }
            else {
                retained.add(ConversationMemoryEntry.fromMessage(nextSequence(conversationId), message));
            }
        }

        List<ConversationMemoryEntry> evicted = new ArrayList<>();
        for (int index = 0; index < currentEntries.size(); index++) {
            if (!matchedIndexes.contains(index)) {
                evicted.add(currentEntries.get(index));
            }
        }
        return new MergeResult(retained, evicted);
    }

    private int findMatchingEntryIndex(List<ConversationMemoryEntry> entries,
                                       Set<Integer> matchedIndexes,
                                       Message message) {
        String messageSignature = signature(message);
        for (int index = 0; index < entries.size(); index++) {
            if (!matchedIndexes.contains(index) && Objects.equals(signature(entries.get(index)), messageSignature)) {
                return index;
            }
        }
        return -1;
    }

    private long nextSequence(String conversationId) {
        Long sequence = redisTemplate.opsForValue().increment(sequenceKey(conversationId));
        if (sequence == null) {
            throw new IllegalStateException("Failed to allocate memory sequence for conversation " + conversationId);
        }
        return sequence;
    }

    private void rewriteWindow(String conversationId, List<ConversationMemoryEntry> retainedEntries) {
        String listKey = listKey(conversationId);
        redisTemplate.delete(listKey);
        if (!retainedEntries.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(listKey, retainedEntries.stream().map(this::serialize).toList());
        }
        refreshTtl(conversationId);
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

    private void refreshTtl(String conversationId) {
        Duration ttl = properties.getMaxRecentAge().multipliedBy(2);
        redisTemplate.expire(listKey(conversationId), ttl);
        redisTemplate.expire(sequenceKey(conversationId), ttl);
    }

    private void summarizeEvicted(String conversationId, List<ConversationMemoryEntry> evictedEntries) {
        longTermMemoryService.scheduleSummaryIfNeeded(resolveUserId(conversationId), conversationId, evictedEntries);
    }

    private String resolveUserId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return DEFAULT_USER_ID;
        }
        int separator = conversationId.indexOf("::");
        String userId = separator < 0 ? conversationId : conversationId.substring(0, separator);
        return StringUtils.hasText(userId) ? userId : DEFAULT_USER_ID;
    }

    private String listKey(String conversationId) {
        return SHORT_TERM_KEY_PREFIX + conversationId + MESSAGE_LIST_SUFFIX;
    }

    private String sequenceKey(String conversationId) {
        return SHORT_TERM_KEY_PREFIX + conversationId + MESSAGE_SEQ_SUFFIX;
    }

    private String conversationIdFromListKey(String key) {
        if (!StringUtils.hasText(key) || !key.startsWith(SHORT_TERM_KEY_PREFIX) || !key.endsWith(MESSAGE_LIST_SUFFIX)) {
            return "";
        }
        return key.substring(SHORT_TERM_KEY_PREFIX.length(), key.length() - MESSAGE_LIST_SUFFIX.length());
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

    private String signature(ConversationMemoryEntry entry) {
        return entry.messageType() + "\n" + entry.text() + "\n" + entry.media().size();
    }

    private String signature(Message message) {
        return signature(ConversationMemoryEntry.fromMessage(-1L, message));
    }

    private record MemoryWindowSnapshot(
            List<ConversationMemoryEntry> retainedEntries,
            List<ConversationMemoryEntry> evictedEntries
    ) {
    }

    private record MergeResult(
            List<ConversationMemoryEntry> retainedEntries,
            List<ConversationMemoryEntry> evictedEntries
    ) {
    }
}
