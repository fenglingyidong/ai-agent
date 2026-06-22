package com.example.ragagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void recentToolCallContextShouldRedactStructuredJsonSensitiveValues() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();

        service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "{\"token\":123,\"password\":true,"
                        + "\"authorization\":{\"scheme\":\"Basic\",\"value\":\"abc123\"},"
                        + "\"nested\":{\"mallToken\":\"mall-secret\"},"
                        + "\"items\":[{\"mallPassword\":\"pass\",\"mallUsername\":\"alice\"}]}",
                "{\"ok\":true}");

        String context = service.recentToolCallContext("alice", "session-1");

        assertFalse(context.contains("\"token\":123"));
        assertFalse(context.contains("\"password\":true"));
        assertFalse(context.contains("abc123"));
        assertFalse(context.contains("mall-secret"));
        assertFalse(context.contains("\"mallPassword\":\"pass\""));
        assertFalse(context.contains("\"mallUsername\":\"alice\""));
        assertTrue(context.contains("\"token\":\"[REDACTED]\""));
        assertTrue(context.contains("\"password\":\"[REDACTED]\""));
        assertTrue(context.contains("\"authorization\":\"[REDACTED]\""));
        assertTrue(context.contains("\"mallToken\":\"[REDACTED]\""));
        assertTrue(context.contains("\"mallPassword\":\"[REDACTED]\""));
        assertTrue(context.contains("\"mallUsername\":\"[REDACTED]\""));
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

    @Test
    void recordsShouldReturnSnapshotWithOlderCallsFolded() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();
        for (int index = 1; index <= 5; index++) {
            service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                    "{\"skuId\":" + index + ",\"note\":\"input-" + index + "\"}",
                    "result-" + index);
        }

        List<ConversationToolCallRecord> records = service.records("alice", "session-1");

        assertEquals(5, records.size());
        assertEquals("mall_get_product_detail", records.get(0).toolName());
        assertEquals(ConversationToolCallRecord.Status.OK, records.get(0).status());
        assertEquals("", records.get(0).errorType());
        assertTrue(records.get(0).input().contains("完整结果已折叠"));
        assertTrue(records.get(0).output().contains("完整结果已折叠"));
        assertFalse(records.get(0).input().contains("input-1"));
        assertFalse(records.get(0).input().contains("\"skuId\":1"));
        assertFalse(records.get(0).output().contains("result-1"));
        assertTrue(records.get(1).input().contains("完整结果已折叠"));
        assertTrue(records.get(1).output().contains("完整结果已折叠"));
        assertFalse(records.get(1).input().contains("input-2"));
        assertFalse(records.get(1).output().contains("result-2"));
        assertTrue(records.get(2).input().contains("\"skuId\":3"));
        assertTrue(records.get(2).output().contains("result-3"));
        assertTrue(records.get(3).input().contains("\"skuId\":4"));
        assertTrue(records.get(3).output().contains("result-4"));
        assertTrue(records.get(4).input().contains("\"skuId\":5"));
        assertTrue(records.get(4).output().contains("result-5"));

        assertThrows(UnsupportedOperationException.class, () -> records.add(records.get(0)));
        assertEquals(5, service.records("alice", "session-1").size());
    }

    @Test
    void recordsAndContextShouldStayBoundedForLongConversations() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();
        for (int index = 1; index <= 25; index++) {
            service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                    "{\"skuId\":" + index + ",\"note\":\"input-" + index + "\"}",
                    "result-" + index);
        }

        List<ConversationToolCallRecord> records = service.records("alice", "session-1");
        String context = service.recentToolCallContext("alice", "session-1");

        assertEquals(20, records.size());
        assertTrue(records.get(0).input().contains("更早工具调用已折叠"));
        assertTrue(records.get(0).output().contains("更早工具调用已折叠"));
        assertFalse(context.contains("result-1"));
        assertFalse(context.contains("input-1"));
        assertTrue(context.contains("result-23"));
        assertTrue(context.contains("result-24"));
        assertTrue(context.contains("result-25"));
        assertFalse(context.contains("[工具调用 21]"));
    }

    @Test
    void recentToolCallContextShouldRedactNonJsonSensitiveFormats() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();

        service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "token=secret-token password: demo123 mallToken=mall-secret "
                        + "mallPassword: pass mallUsername=alice authorization=Basic abc123",
                "Authorization: Bearer abc+/=:xyz");

        String context = service.recentToolCallContext("alice", "session-1");

        assertFalse(context.contains("secret-token"));
        assertFalse(context.contains("demo123"));
        assertFalse(context.contains("mall-secret"));
        assertFalse(context.contains("mallPassword: pass"));
        assertFalse(context.contains("alice"));
        assertFalse(context.contains("abc123"));
        assertFalse(context.contains("abc+/=:xyz"));
        assertTrue(context.contains("[REDACTED]"));
    }

    @Test
    void recentToolCallContextShouldRedactExtendedSensitiveFields() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();

        service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "accessToken=access-secret refresh_token: refresh-secret skuId=3020",
                "{\"authToken\":\"auth-secret\",\"apiKey\":\"key-secret\","
                        + "\"clientSecret\":\"client-secret\",\"nested\":{\"ACCESS_TOKEN\":\"upper-secret\"},"
                        + "\"skuId\":3020}");

        String context = service.recentToolCallContext("alice", "session-1");

        assertFalse(context.contains("access-secret"));
        assertFalse(context.contains("refresh-secret"));
        assertFalse(context.contains("auth-secret"));
        assertFalse(context.contains("key-secret"));
        assertFalse(context.contains("client-secret"));
        assertFalse(context.contains("upper-secret"));
        assertTrue(context.contains("accessToken=[REDACTED]"));
        assertTrue(context.contains("refresh_token: [REDACTED]"));
        assertTrue(context.contains("\"authToken\":\"[REDACTED]\""));
        assertTrue(context.contains("\"apiKey\":\"[REDACTED]\""));
        assertTrue(context.contains("\"clientSecret\":\"[REDACTED]\""));
        assertTrue(context.contains("\"ACCESS_TOKEN\":\"[REDACTED]\""));
        assertTrue(context.contains("skuId=3020"));
        assertTrue(context.contains("\"skuId\":3020"));
    }

    @Test
    void extendedSensitiveRedactionShouldPreserveAdjacentNormalFacts() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();

        service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "authToken=auth-secret skuId=3020 product=键盘",
                "apiKey: key-secret stock=260 clientSecret=client-secret price=199");

        String context = service.recentToolCallContext("alice", "session-1");

        assertFalse(context.contains("auth-secret"));
        assertFalse(context.contains("key-secret"));
        assertFalse(context.contains("client-secret"));
        assertTrue(context.contains("skuId=3020"));
        assertTrue(context.contains("product=键盘"));
        assertTrue(context.contains("stock=260"));
        assertTrue(context.contains("price=199"));
    }

    @Test
    void redactionShouldPreserveAdjacentNormalFacts() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();

        service.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "token=secret-token skuId=3020 product=键盘",
                "Authorization: Bearer abc+/=:xyz, skuId=3020, stock=260");
        service.rememberSuccess("alice", "session-1", "rag_search",
                "{\"note\":\"Bearer json-token+/=:\",\"skuId\":3020,\"stock\":260}",
                "ok");

        String context = service.recentToolCallContext("alice", "session-1");

        assertFalse(context.contains("secret-token"));
        assertFalse(context.contains("abc+/=:xyz"));
        assertFalse(context.contains("json-token+/=:"));
        assertTrue(context.contains("skuId=3020"));
        assertTrue(context.contains("product=键盘"));
        assertTrue(context.contains("stock=260"));
        assertTrue(context.contains("\"skuId\":3020"));
        assertTrue(context.contains("\"stock\":260"));
    }

    @Test
    void recentToolCallContextShouldTruncateLongText() {
        ConversationToolCallMemoryService service = new ConversationToolCallMemoryService();
        String longInput = "a".repeat(5000);

        service.rememberSuccess("alice", "session-1", "mall_get_product_detail", longInput, "ok");

        String context = service.recentToolCallContext("alice", "session-1");

        assertTrue(context.contains("内容已截断。"));
        assertTrue(context.length() < 4300);
    }
}
