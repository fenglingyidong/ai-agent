package com.example.ragagent.controller;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.conversation.ConversationTurnRecord;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationControllerTest {

    @Test
    void turnsShouldReturnCurrentUsersRecentTurns() {
        ConversationLogService service = mock(ConversationLogService.class);
        ConversationController controller = new ConversationController(service);
        ConversationTurnRecord record = ConversationTurnRecord.started(
                "turn-1", "alice", "session-1", 1L, "qwen", false, "问题", 0, 100L
        ).complete("回答", 200L);
        when(service.listRecentTurns("alice", "session-1", 20)).thenReturn(List.of(record));

        ConversationController.ConversationTurnsResponse response = controller.turns(
                "session-1",
                20,
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of())
        );

        assertEquals("session-1", response.sessionId());
        assertEquals(1, response.items().size());
        assertEquals("问题", response.items().get(0).userText());
        assertEquals("回答", response.items().get(0).assistantText());
    }

    @Test
    void deleteSessionShouldUseCurrentUser() {
        ConversationLogService service = mock(ConversationLogService.class);
        ConversationController controller = new ConversationController(service);

        controller.deleteSession(
                "session-1",
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of())
        );

        verify(service).deleteSession("alice", "session-1");
    }
}
