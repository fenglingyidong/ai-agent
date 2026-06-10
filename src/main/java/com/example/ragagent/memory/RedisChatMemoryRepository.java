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
import java.util.concurrent.CompletableFuture;

@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String SHORT_TERM_KEY_PREFIX = "memory:short:";
    private static final String CONVERSATION_SET_KEY = SHORT_TERM_KEY_PREFIX + "conversations";
    private static final String MESSAGE_LIST_SUFFIX = ":messages";
    private static final String MESSAGE_SEQ_SUFFIX = ":sequence";
    private static final String STATE_SUFFIX = ":state";
    private static final String LAST_TOUCHED_AT_FIELD = "lastTouchedAt";
    private static final String LAST_SUMMARIZED_SEQUENCE_FIELD = "lastSummarizedSequence";
    private static final String LAST_SUMMARY_ATTEMPT_AT_FIELD = "lastSummaryAttemptAt";
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
        Set<String> conversationIds = redisTemplate.opsForSet().members(CONVERSATION_SET_KEY);
        if (conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        return conversationIds.stream()
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

        markTouched(conversationId);
        List<ConversationMemoryEntry> currentEntries = readAllEntries(conversationId);
        MemoryWindowSnapshot ageSnapshot = compactByAge(conversationId, currentEntries);
        summarizeEvicted(conversationId, ageSnapshot.evictedEntries());
        List<ConversationMemoryEntry> retainedEntries = ageSnapshot.retainedEntries();
        if (ageSnapshot.evictedEntries().isEmpty() && isPrefixAppend(retainedEntries, messages)) {
            List<ConversationMemoryEntry> appendedEntries = appendNewMessages(conversationId, retainedEntries, messages);
            summarizeEvicted(conversationId, countEvictedByTrim(retainedEntries, appendedEntries));
            refreshTtl(conversationId);
            return;
        }
        MergeResult mergeResult = mergeSavedMessages(conversationId, retainedEntries, messages);
        rewriteWindow(conversationId, mergeResult.retainedEntries(), false);
        summarizeEvicted(conversationId, mergeResult.evictedEntries());
        refreshTtl(conversationId);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        redisTemplate.delete(listKey(conversationId));
        redisTemplate.delete(sequenceKey(conversationId));
        redisTemplate.delete(stateKey(conversationId));
        redisTemplate.opsForSet().remove(CONVERSATION_SET_KEY, conversationId);
    }

    public int summarizeIdleConversations() {
        Instant now = Instant.now();
        int scheduledCount = 0;
        for (String conversationId : findConversationIds()) {
            MemoryState state = readState(conversationId);
            if (state.lastTouchedAt() <= 0 || now.toEpochMilli() - state.lastTouchedAt() < properties.getIdleSummaryAge().toMillis()) {
                removeInactiveConversationIfEmpty(conversationId, state);
                continue;
            }

            List<ConversationMemoryEntry> entriesToSummarize = readAllEntries(conversationId).stream()
                    .filter(entry -> entry.sequence() > state.lastSummarizedSequence())
                    .toList();
            if (entriesToSummarize.isEmpty()) {
                continue;
            }
            if (state.lastSummaryAttemptAt() >= state.lastTouchedAt()) {
                continue;
            }

            redisTemplate.opsForHash().put(stateKey(conversationId), LAST_SUMMARY_ATTEMPT_AT_FIELD, Long.toString(now.toEpochMilli()));
            refreshTtl(conversationId);
            scheduleSummary(conversationId, entriesToSummarize);
            scheduledCount++;
        }
        return scheduledCount;
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
            return new MemoryWindowSnapshot(List.copyOf(entries), List.of());
        }

        List<ConversationMemoryEntry> evicted = new ArrayList<>(entries.subList(0, keepFromIndex));
        List<ConversationMemoryEntry> retained = new ArrayList<>(entries.subList(keepFromIndex, entries.size()));
        rewriteWindow(conversationId, retained, true);
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

    private boolean isPrefixAppend(List<ConversationMemoryEntry> currentEntries, List<Message> savedMessages) {
        if (savedMessages.size() <= currentEntries.size()) {
            return false;
        }
        for (int index = 0; index < currentEntries.size(); index++) {
            if (!Objects.equals(signature(currentEntries.get(index)), signature(savedMessages.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private List<ConversationMemoryEntry> appendNewMessages(String conversationId,
                                                            List<ConversationMemoryEntry> currentEntries,
                                                            List<Message> savedMessages) {
        List<ConversationMemoryEntry> appendedEntries = new ArrayList<>();
        for (int index = currentEntries.size(); index < savedMessages.size(); index++) {
            ConversationMemoryEntry entry = ConversationMemoryEntry.fromMessage(nextSequence(conversationId), savedMessages.get(index));
            redisTemplate.opsForList().rightPush(listKey(conversationId), serialize(entry));
            appendedEntries.add(entry);
        }
        redisTemplate.opsForList().trim(listKey(conversationId), -properties.getMaxRecentMessages(), -1);
        return appendedEntries;
    }

    private List<ConversationMemoryEntry> countEvictedByTrim(List<ConversationMemoryEntry> currentEntries,
                                                             List<ConversationMemoryEntry> appendedEntries) {
        int maxMessages = properties.getMaxRecentMessages();
        int combinedSize = currentEntries.size() + appendedEntries.size();
        int evictedCount = Math.max(0, combinedSize - maxMessages);
        if (evictedCount <= 0) {
            return List.of();
        }
        return List.copyOf(currentEntries.subList(0, Math.min(evictedCount, currentEntries.size())));
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

    private void rewriteWindow(String conversationId, List<ConversationMemoryEntry> retainedEntries, boolean refreshTtl) {
        String listKey = listKey(conversationId);
        redisTemplate.delete(listKey);
        if (!retainedEntries.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(listKey, retainedEntries.stream().map(this::serialize).toList());
        }
        if (refreshTtl) {
            refreshTtl(conversationId);
        }
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
        Duration ttl = properties.getShortTermTtl();
        redisTemplate.expire(listKey(conversationId), ttl);
        redisTemplate.expire(sequenceKey(conversationId), ttl);
        redisTemplate.expire(stateKey(conversationId), ttl);
    }

    private void summarizeEvicted(String conversationId, List<ConversationMemoryEntry> evictedEntries) {
        if (evictedEntries == null || evictedEntries.isEmpty()) {
            return;
        }
        long lastSummarizedSequence = readState(conversationId).lastSummarizedSequence();
        List<ConversationMemoryEntry> unsummarizedEntries = evictedEntries.stream()
                .filter(entry -> entry.sequence() > lastSummarizedSequence)
                .toList();
        scheduleSummary(conversationId, unsummarizedEntries);
    }

    private void scheduleSummary(String conversationId, List<ConversationMemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        long toSequence = entries.stream()
                .mapToLong(ConversationMemoryEntry::sequence)
                .max()
                .orElse(0L);
        CompletableFuture<Boolean> future =
                longTermMemoryService.scheduleSummaryIfNeeded(resolveUserId(conversationId), conversationId, entries);
        if (future != null) {
            future.thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    updateLastSummarizedSequence(conversationId, toSequence);
                    return;
                }
                clearLastSummaryAttempt(conversationId);
            });
        }
    }

    private void markTouched(String conversationId) {
        redisTemplate.opsForSet().add(CONVERSATION_SET_KEY, conversationId);
        redisTemplate.opsForHash().put(stateKey(conversationId), LAST_TOUCHED_AT_FIELD, Long.toString(System.currentTimeMillis()));
    }

    private MemoryState readState(String conversationId) {
        Object lastTouchedAt = redisTemplate.opsForHash().get(stateKey(conversationId), LAST_TOUCHED_AT_FIELD);
        Object lastSummarizedSequence = redisTemplate.opsForHash().get(stateKey(conversationId), LAST_SUMMARIZED_SEQUENCE_FIELD);
        Object lastSummaryAttemptAt = redisTemplate.opsForHash().get(stateKey(conversationId), LAST_SUMMARY_ATTEMPT_AT_FIELD);
        return new MemoryState(
                parseLong(lastTouchedAt, 0L),
                parseLong(lastSummarizedSequence, 0L),
                parseLong(lastSummaryAttemptAt, 0L)
        );
    }

    private void updateLastSummarizedSequence(String conversationId, long sequence) {
        if (sequence <= 0) {
            return;
        }
        MemoryState state = readState(conversationId);
        long nextSequence = Math.max(state.lastSummarizedSequence(), sequence);
        redisTemplate.opsForHash().put(stateKey(conversationId), LAST_SUMMARIZED_SEQUENCE_FIELD, Long.toString(nextSequence));
        refreshTtl(conversationId);
    }

    private void clearLastSummaryAttempt(String conversationId) {
        redisTemplate.opsForHash().put(stateKey(conversationId), LAST_SUMMARY_ATTEMPT_AT_FIELD, "0");
        refreshTtl(conversationId);
    }

    private void removeInactiveConversationIfEmpty(String conversationId, MemoryState state) {
        if (state.lastTouchedAt() > 0) {
            return;
        }
        if (readAllEntries(conversationId).isEmpty()) {
            redisTemplate.opsForSet().remove(CONVERSATION_SET_KEY, conversationId);
        }
    }

    private long parseLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.toString());
        }
        catch (NumberFormatException ex) {
            return defaultValue;
        }
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

    private String stateKey(String conversationId) {
        return SHORT_TERM_KEY_PREFIX + conversationId + STATE_SUFFIX;
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

    private record MemoryState(
            long lastTouchedAt,
            long lastSummarizedSequence,
            long lastSummaryAttemptAt
    ) {
    }
}
