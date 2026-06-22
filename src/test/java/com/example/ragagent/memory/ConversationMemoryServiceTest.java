package com.example.ragagent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    @Test
    void recentConversationContextShouldAppendToolCallContext() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ConversationToolCallMemoryService toolCallMemoryService = new ConversationToolCallMemoryService();
        ConversationMemoryService service = new ConversationMemoryService(chatMemory, toolCallMemoryService);
        when(chatMemory.get("alice::session-1")).thenReturn(List.of(
                new UserMessage("查一下商品 3020"),
                new AssistantMessage("我来查询"),
                new UserMessage("库存还有吗"),
                new AssistantMessage("需要工具结果确认")
        ));
        toolCallMemoryService.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "{\"skuId\":3020}", "库存 260 件");

        String context = service.recentConversationContext("alice", "session-1");

        assertTrue(context.contains("最近 2 轮短期对话上下文"));
        assertTrue(context.contains("用户：查一下商品 3020"));
        assertTrue(context.contains("助手：需要工具结果确认"));
        assertTrue(context.contains("最近工具调用上下文"));
        assertTrue(context.contains("mall_get_product_detail"));
        assertTrue(context.contains("库存 260 件"));
    }

    @Test
    void recentConversationContextShouldReturnToolContextWhenChatMemoryIsEmpty() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ConversationToolCallMemoryService toolCallMemoryService = new ConversationToolCallMemoryService();
        ConversationMemoryService service = new ConversationMemoryService(chatMemory, toolCallMemoryService);
        when(chatMemory.get("alice::session-1")).thenReturn(List.of());
        toolCallMemoryService.rememberSuccess("alice", "session-1", "mall_get_product_detail",
                "{\"skuId\":3020}", "库存 260 件");

        String context = service.recentConversationContext("alice", "session-1");

        assertFalse(context.contains("最近 2 轮短期对话上下文"));
        assertTrue(context.contains("最近工具调用上下文"));
        assertTrue(context.contains("mall_get_product_detail"));
        assertTrue(context.contains("库存 260 件"));
    }
}
