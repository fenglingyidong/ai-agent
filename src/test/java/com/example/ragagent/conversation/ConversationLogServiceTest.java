package com.example.ragagent.conversation;

import com.example.ragagent.conversation.entity.ConversationTurnEntity;
import com.example.ragagent.conversation.mapper.ConversationSessionMapper;
import com.example.ragagent.conversation.mapper.ConversationTurnMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationLogServiceTest {

    @Test
    void beginTurnShouldAllocateTurnNoAndInsertProcessingTurn() {
        ConversationSessionMapper sessionMapper = mock(ConversationSessionMapper.class);
        ConversationTurnMapper turnMapper = mock(ConversationTurnMapper.class);
        ConversationLogService service = new ConversationLogService(
                sessionMapper,
                turnMapper,
                new ObjectMapper(),
                new ConversationLogProperties()
        );
        when(sessionMapper.lockNextTurnNo("alice", "session-1")).thenReturn(1L);

        ConversationTurnRecord record = service.beginTurn("alice", "session-1", "qwen", true, "问题", 0);

        assertEquals(1L, record.turnNo());
        assertEquals(ConversationTurnStatus.PROCESSING, record.status());
        verify(sessionMapper).upsertSession(eq("alice"), eq("session-1"), any());
        verify(sessionMapper).updateNextTurnNo(eq("alice"), eq("session-1"), eq(2L), any());
        verify(turnMapper).insert(any(ConversationTurnEntity.class));
        verify(turnMapper).deleteOverflowTurns("alice", "session-1", 1000);
    }

    @Test
    void completeTurnShouldUpdateVisibleAssistantText() {
        ConversationSessionMapper sessionMapper = mock(ConversationSessionMapper.class);
        ConversationTurnMapper turnMapper = mock(ConversationTurnMapper.class);
        ConversationLogService service = new ConversationLogService(
                sessionMapper,
                turnMapper,
                new ObjectMapper(),
                new ConversationLogProperties()
        );
        ConversationTurnRecord started = ConversationTurnRecord.started(
                "turn-1",
                "alice",
                "session-1",
                1L,
                "qwen",
                true,
                "问题",
                0,
                100L
        );

        service.completeTurn(started, "最终回答");

        verify(turnMapper).updateTurnResult(
                eq("turn-1"),
                eq("最终回答"),
                eq("COMPLETED"),
                eq(null),
                any(),
                any(),
                any()
        );
    }

    @Test
    void disabledServiceShouldNotWriteMappers() {
        ConversationSessionMapper sessionMapper = mock(ConversationSessionMapper.class);
        ConversationTurnMapper turnMapper = mock(ConversationTurnMapper.class);
        ConversationLogProperties properties = new ConversationLogProperties();
        properties.setEnabled(false);
        ConversationLogService service = new ConversationLogService(
                sessionMapper,
                turnMapper,
                new ObjectMapper(),
                properties
        );

        ConversationTurnRecord record = service.beginTurn("alice", "session-1", "qwen", true, "问题", 0);
        service.completeTurn(null, "回答");
        service.completeTurn(record, "回答");

        assertNull(record);
        verify(sessionMapper, never()).upsertSession(any(), any(), any());
        verify(turnMapper, never()).insert(any(ConversationTurnEntity.class));
    }

    @Test
    void listRecentTurnsShouldConvertEntitiesToRecords() {
        ConversationSessionMapper sessionMapper = mock(ConversationSessionMapper.class);
        ConversationTurnMapper turnMapper = mock(ConversationTurnMapper.class);
        ConversationLogService service = new ConversationLogService(
                sessionMapper,
                turnMapper,
                new ObjectMapper(),
                new ConversationLogProperties()
        );
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setTurnId("turn-1");
        entity.setUserId("alice");
        entity.setSessionId("session-1");
        entity.setTurnNo(1L);
        entity.setModelId("qwen");
        entity.setWebSearchEnabled(true);
        entity.setUserText("问题");
        entity.setAssistantText("回答");
        entity.setStatus("COMPLETED");
        entity.setErrorMessage(null);
        entity.setMetadataJson("{\"mediaCount\":0}");
        entity.setCreatedAt(LocalDateTime.of(2026, 5, 24, 12, 0));
        entity.setCompletedAt(LocalDateTime.of(2026, 5, 24, 12, 1));
        when(turnMapper.selectRecentTurns("alice", "session-1", 20)).thenReturn(List.of(entity));

        List<ConversationTurnRecord> records = service.listRecentTurns("alice", "session-1", 20);

        assertEquals(1, records.size());
        assertEquals("问题", records.get(0).userText());
        assertEquals("回答", records.get(0).assistantText());
    }
}
