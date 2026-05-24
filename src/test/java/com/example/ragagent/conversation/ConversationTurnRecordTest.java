package com.example.ragagent.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnRecordTest {

    @Test
    void completeShouldKeepUserTextAndPersistVisibleAssistantText() {
        ConversationTurnRecord started = ConversationTurnRecord.started(
                "turn-1",
                "alice",
                "session-1",
                1L,
                "qwen",
                true,
                "帮我推荐跑鞋",
                2,
                100L
        );

        ConversationTurnRecord completed = started.complete("推荐云跑 AirLite。", 200L);

        assertEquals("turn-1", completed.id());
        assertEquals("alice", completed.userId());
        assertEquals("session-1", completed.sessionId());
        assertEquals(1L, completed.turnNo());
        assertEquals("帮我推荐跑鞋", completed.userText());
        assertEquals("推荐云跑 AirLite。", completed.assistantText());
        assertEquals(ConversationTurnStatus.COMPLETED, completed.status());
        assertEquals(100L, completed.createdAtEpochMillis());
        assertEquals(200L, completed.completedAtEpochMillis());
        assertEquals(2, completed.metadata().get("mediaCount"));
    }

    @Test
    void failShouldRecordPartialAnswerAndErrorMessage() {
        ConversationTurnRecord started = ConversationTurnRecord.started(
                "turn-2",
                "alice",
                "session-1",
                2L,
                "",
                false,
                "查购物车",
                0,
                100L
        );

        ConversationTurnRecord failed = started.fail("已输出一半", "模型连接中断", 250L);

        assertEquals(ConversationTurnStatus.FAILED, failed.status());
        assertEquals("已输出一半", failed.assistantText());
        assertEquals("模型连接中断", failed.errorMessage());
        assertEquals(250L, failed.completedAtEpochMillis());
    }

    @Test
    void metadataShouldBeImmutable() {
        ConversationTurnRecord started = ConversationTurnRecord.started(
                "turn-3",
                "alice",
                "session-1",
                3L,
                "qwen",
                false,
                "问题",
                0,
                100L
        );

        assertThrows(UnsupportedOperationException.class, () -> started.metadata().put("x", 1));
    }
}
