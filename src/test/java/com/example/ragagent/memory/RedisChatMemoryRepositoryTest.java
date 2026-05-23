package com.example.ragagent.memory;

import com.example.ragagent.config.HierarchicalMemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryRepositoryTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private ValueOperations<String, String> valueOperations;
    private SetOperations<String, String> setOperations;
    private HashOperations<String, Object, Object> hashOperations;
    private LongTermMemoryService longTermMemoryService;
    private RedisChatMemoryRepository repository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        valueOperations = mock(ValueOperations.class);
        setOperations = mock(SetOperations.class);
        hashOperations = mock(HashOperations.class);
        longTermMemoryService = mock(LongTermMemoryService.class);
        objectMapper = new ObjectMapper();

        HierarchicalMemoryProperties properties = new HierarchicalMemoryProperties();
        properties.setMaxRecentMessages(2);
        properties.setMaxRecentAge(Duration.ofHours(1));
        properties.setShortTermTtl(Duration.ofHours(2));
        properties.setIdleSummaryAge(Duration.ofMinutes(30));

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        repository = new RedisChatMemoryRepository(redisTemplate, objectMapper, properties, longTermMemoryService);
    }

    @Test
    void findConversationIdsShouldReturnIdsFromActiveConversationSet() {
        when(setOperations.members("memory:short:conversations")).thenReturn(Set.of(
                "user-2::session-2",
                "user-1::session-1"
        ));

        assertEquals(List.of("user-1::session-1", "user-2::session-2"), repository.findConversationIds());
    }

    @Test
    void findByConversationIdShouldReadMessagesAndRefreshTtl() throws Exception {
        ConversationMemoryEntry entry = ConversationMemoryEntry.fromMessage(1L, new UserMessage("hello"));
        when(listOperations.range("memory:short:user-1::session-1:messages", 0, -1))
                .thenReturn(List.of(objectMapper.writeValueAsString(entry)));

        List<Message> messages = repository.findByConversationId("user-1::session-1");

        assertEquals(1, messages.size());
        assertEquals("hello", ((UserMessage) messages.get(0)).getText());
        verify(redisTemplate, times(2)).expire("memory:short:user-1::session-1:messages", Duration.ofHours(2));
        verify(redisTemplate, times(2)).expire("memory:short:user-1::session-1:sequence", Duration.ofHours(2));
        verify(redisTemplate, times(2)).expire("memory:short:user-1::session-1:state", Duration.ofHours(2));
    }

    @Test
    void messageWindowChatMemoryShouldEvictOldestMessageByCount() throws Exception {
        ConversationMemoryEntry first = new ConversationMemoryEntry(1L, System.currentTimeMillis(), "USER", "first", List.of(), null);
        ConversationMemoryEntry second = new ConversationMemoryEntry(2L, System.currentTimeMillis(), "USER", "second", List.of(), null);
        when(listOperations.range("memory:short:user-1::session-1:messages", 0, -1))
                .thenReturn(List.of(
                        objectMapper.writeValueAsString(first),
                        objectMapper.writeValueAsString(second)
                ));
        when(valueOperations.increment("memory:short:user-1::session-1:sequence")).thenReturn(3L);

        MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(2)
                .build()
                .add("user-1::session-1", new UserMessage("third"));

        verify(longTermMemoryService).scheduleSummaryIfNeeded("user-1", "user-1::session-1", List.of(first));
    }

    @Test
    void findByConversationIdShouldEvictExpiredMessagesByAge() throws Exception {
        long now = System.currentTimeMillis();
        ConversationMemoryEntry expired = new ConversationMemoryEntry(1L, now - Duration.ofHours(2).toMillis(), "USER", "expired", List.of(), null);
        ConversationMemoryEntry retained = new ConversationMemoryEntry(2L, now, "USER", "retained", List.of(), null);
        when(listOperations.range("memory:short:user-1::session-1:messages", 0, -1))
                .thenReturn(List.of(
                        objectMapper.writeValueAsString(expired),
                        objectMapper.writeValueAsString(retained)
                ));

        List<Message> messages = repository.findByConversationId("user-1::session-1");

        assertEquals(1, messages.size());
        assertEquals("retained", ((UserMessage) messages.get(0)).getText());
        verify(redisTemplate).delete("memory:short:user-1::session-1:messages");
        verify(longTermMemoryService).scheduleSummaryIfNeeded("user-1", "user-1::session-1", List.of(expired));
    }

    @Test
    void summarizeIdleConversationsShouldSummarizeUnsummarizedWindowBeforeTtlCleanup() throws Exception {
        long now = System.currentTimeMillis();
        ConversationMemoryEntry alreadySummarized = new ConversationMemoryEntry(1L, now, "USER", "old", List.of(), null);
        ConversationMemoryEntry unsummarized = new ConversationMemoryEntry(2L, now, "USER", "new", List.of(), null);

        when(setOperations.members("memory:short:conversations"))
                .thenReturn(Set.of("user-1::session-1"));
        when(hashOperations.get("memory:short:user-1::session-1:state", "lastTouchedAt"))
                .thenReturn(Long.toString(now - Duration.ofHours(1).toMillis()));
        when(hashOperations.get("memory:short:user-1::session-1:state", "lastSummarizedSequence"))
                .thenReturn("1");
        when(listOperations.range("memory:short:user-1::session-1:messages", 0, -1))
                .thenReturn(List.of(
                        objectMapper.writeValueAsString(alreadySummarized),
                        objectMapper.writeValueAsString(unsummarized)
                ));
        when(longTermMemoryService.scheduleSummaryIfNeeded("user-1", "user-1::session-1", List.of(unsummarized)))
                .thenReturn(CompletableFuture.completedFuture(true));

        assertEquals(1, repository.summarizeIdleConversations());

        verify(hashOperations).put(eq("memory:short:user-1::session-1:state"), eq("lastSummaryAttemptAt"), anyString());
        verify(hashOperations).put("memory:short:user-1::session-1:state", "lastSummarizedSequence", "2");
    }

    @Test
    void summarizeIdleConversationsShouldSkipAlreadySummarizedWindow() throws Exception {
        long now = System.currentTimeMillis();
        ConversationMemoryEntry entry = new ConversationMemoryEntry(2L, now, "USER", "new", List.of(), null);

        when(setOperations.members("memory:short:conversations"))
                .thenReturn(Set.of("user-1::session-1"));
        when(hashOperations.get("memory:short:user-1::session-1:state", "lastTouchedAt"))
                .thenReturn(Long.toString(now - Duration.ofHours(1).toMillis()));
        when(hashOperations.get("memory:short:user-1::session-1:state", "lastSummarizedSequence"))
                .thenReturn("2");
        when(listOperations.range("memory:short:user-1::session-1:messages", 0, -1))
                .thenReturn(List.of(objectMapper.writeValueAsString(entry)));

        assertEquals(0, repository.summarizeIdleConversations());
    }

    @Test
    void summarizeIdleConversationsShouldSkipWindowAlreadyBeingSummarized() throws Exception {
        long now = System.currentTimeMillis();
        ConversationMemoryEntry entry = new ConversationMemoryEntry(2L, now, "USER", "new", List.of(), null);

        when(setOperations.members("memory:short:conversations"))
                .thenReturn(Set.of("user-1::session-1"));
        when(hashOperations.get("memory:short:user-1::session-1:state", "lastTouchedAt"))
                .thenReturn(Long.toString(now - Duration.ofHours(1).toMillis()));
        when(hashOperations.get("memory:short:user-1::session-1:state", "lastSummarizedSequence"))
                .thenReturn("1");
        when(hashOperations.get("memory:short:user-1::session-1:state", "lastSummaryAttemptAt"))
                .thenReturn(Long.toString(now));
        when(listOperations.range("memory:short:user-1::session-1:messages", 0, -1))
                .thenReturn(List.of(objectMapper.writeValueAsString(entry)));

        assertEquals(0, repository.summarizeIdleConversations());

        verify(longTermMemoryService, never()).scheduleSummaryIfNeeded(anyString(), anyString(), any());
    }
}
