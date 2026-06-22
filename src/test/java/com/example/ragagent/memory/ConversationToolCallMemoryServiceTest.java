package com.example.ragagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationToolCallMemoryServiceTest {

    @Test
    void recentToolCallContextShouldFoldOlderCallsAndKeepLatestThreeInAppendOrder() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();
        for (int index = 1; index <= 5; index++) {
            service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                    "{\"skuId\":" + index + "}", "result-" + index);
        }

        String context = service.recentToolCallContext("alice", "session-1");

        assertTrue(context.contains("最近工具调用上下文"));
        assertTrue(context.contains("[工具调用 1] 已调用 mall_get_product_detail，完整结果已折叠"));
        assertTrue(context.contains("[工具调用 2] 已调用 mall_get_product_detail，完整结果已折叠"));
        assertFalse(context.contains("result-1"));
        assertFalse(context.contains("result-2"));
        assertTrue(context.contains("[工具调用 3] mall_get_product_detail"));
        assertTrue(context.contains("输入：{\"skuId\":3}"));
        assertTrue(context.contains("结果：result-3"));
        assertTrue(context.contains("[工具调用 4] mall_get_product_detail"));
        assertTrue(context.contains("结果：result-4"));
        assertTrue(context.contains("[工具调用 5] mall_get_product_detail"));
        assertTrue(context.contains("结果：result-5"));
    }

    @Test
    void recentToolCallContextShouldRedactSensitiveFields() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();

        service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "{\"skuId\":3020,\"mallToken\":\"secret-token\",\"password\":\"demo123\"}",
                "{\"ok\":true,\"authorization\":\"Bearer secret-token\"}");

        String context = service.recentToolCallContext("alice", "session-1");

        assertFalse(context.contains("secret-token"));
        assertFalse(context.contains("demo123"));
        assertTrue(context.contains("\"mallToken\":\"[REDACTED]\""));
        assertTrue(context.contains("\"password\":\"[REDACTED]\""));
        assertTrue(context.contains("\"authorization\":\"[REDACTED]\""));
    }

    @Test
    void recentToolCallContextShouldRenderErrorWithoutRawExceptionMessage() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();

        service.rememberError("alice", "session-1", "mall_get_product_detail",
                "{\"skuId\":3020,\"token\":\"secret-token\"}",
                new RuntimeException("failed token=secret-token input={\"skuId\":3020}"));

        String context = service.recentToolCallContext("alice", "session-1");

        assertTrue(context.contains("状态：ERROR"));
        assertTrue(context.contains("错误类型：RuntimeException"));
        assertTrue(context.contains("结果：工具调用失败，完整错误已省略。"));
        assertFalse(context.contains("failed token="));
        assertFalse(context.contains("secret-token"));
    }
}
