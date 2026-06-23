package com.example.ragagent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    @Test
    void recentMessagesShouldReturnTailWindow() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ConversationMemoryService service = new ConversationMemoryService(chatMemory);
        when(chatMemory.get("alice::session-1")).thenReturn(List.of(
                new UserMessage("第一轮问题"),
                new AssistantMessage("第一轮回答"),
                new UserMessage("第二轮问题"),
                new AssistantMessage("第二轮回答"),
                new UserMessage("第三轮问题"),
                new AssistantMessage("第三轮回答")
        ));

        List<Message> messages = service.recentMessages("alice", "session-1", 4);

        assertEquals(4, messages.size());
        assertEquals("第二轮问题", ((UserMessage) messages.get(0)).getText());
        assertEquals("第三轮回答", ((AssistantMessage) messages.get(3)).getText());
    }

    @Test
    void recentConversationContextShouldRenderRecentTwoTurnsWithUsageRules() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ConversationMemoryService service = new ConversationMemoryService(chatMemory);
        when(chatMemory.get("alice::session-1")).thenReturn(List.of(
                new UserMessage("婴儿湿巾 80 抽 12 包这款多少钱？"),
                new AssistantMessage("婴儿湿巾 80抽 12包 SKU 4056，价格 99.00 元，库存 300 件。")
        ));

        String context = service.recentConversationContext("alice", "session-1");

        assertTrue(context.contains("最近 2 轮短期对话上下文"));
        assertTrue(context.contains("用户：婴儿湿巾 80 抽 12 包这款多少钱？"));
        assertTrue(context.contains("助手：婴儿湿巾 80抽 12包 SKU 4056"));
        assertTrue(context.contains("禁止猜 SKU"));
    }

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
        assertTrue(context.indexOf("最近 2 轮短期对话上下文") < context.indexOf("最近工具调用上下文"));
        assertTrue(context.indexOf("需要工具结果确认") < context.indexOf("使用规则："));
        assertTrue(context.indexOf("使用规则：") < context.indexOf("最近工具调用上下文"));
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

    @Test
    void recentConversationContextShouldUseDefaultSessionForBlankSessionId() {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
        ConversationToolCallMemoryService toolCallMemoryService = new ConversationToolCallMemoryService();
        ConversationMemoryService service = new ConversationMemoryService(chatMemory, toolCallMemoryService);
        service.rememberTurn("alice", "  ", "默认会话问题", "默认会话回答");
        toolCallMemoryService.rememberSuccess("alice", "  ", "mall_get_product_detail",
                "{\"skuId\":3020}", "默认工具结果");

        String context = service.recentConversationContext("alice", " ");

        assertTrue(context.contains("最近 2 轮短期对话上下文"));
        assertTrue(context.contains("用户：默认会话问题"));
        assertTrue(context.contains("助手：默认会话回答"));
        assertTrue(context.contains("最近工具调用上下文"));
        assertTrue(context.contains("mall_get_product_detail"));
        assertTrue(context.contains("默认工具结果"));
    }

    @Test
    void recentConversationContextShouldRenderOnlyLatestFourShortTermMessages() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ConversationMemoryService service = new ConversationMemoryService(chatMemory);
        when(chatMemory.get("alice::session-1")).thenReturn(List.of(
                new UserMessage("第一条"),
                new AssistantMessage("第二条"),
                new UserMessage("第三条"),
                new AssistantMessage("第四条"),
                new UserMessage("第五条"),
                new AssistantMessage("第六条")
        ));

        String context = service.recentConversationContext("alice", "session-1");

        assertFalse(context.contains("第一条"));
        assertFalse(context.contains("第二条"));
        assertTrue(context.contains("用户：第三条"));
        assertTrue(context.contains("助手：第四条"));
        assertTrue(context.contains("用户：第五条"));
        assertTrue(context.contains("助手：第六条"));
    }

    @Test
    void singleArgumentConstructorShouldReturnOnlyShortTermContext() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ConversationMemoryService service = new ConversationMemoryService(chatMemory);
        when(chatMemory.get("alice::session-1")).thenReturn(List.of(
                new UserMessage("只查短期上下文"),
                new AssistantMessage("没有工具上下文")
        ));

        String context = service.recentConversationContext("alice", "session-1");

        assertEquals("", service.recentToolCallContext("alice", "session-1"));
        assertTrue(context.contains("最近 2 轮短期对话上下文"));
        assertTrue(context.contains("用户：只查短期上下文"));
        assertTrue(context.contains("助手：没有工具上下文"));
        assertFalse(context.contains("最近工具调用上下文"));
    }
}
