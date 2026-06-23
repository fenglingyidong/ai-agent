package com.example.ragagent.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 封装短期对话记忆写入逻辑，把用户和助手消息追加到 Spring AI ChatMemory。
 */
@Service
public class ConversationMemoryService {

    private static final String DEFAULT_USER_ID = "anonymous";
    private static final String DEFAULT_SESSION_ID = "default";
    private static final int RECENT_CONVERSATION_CONTEXT_MESSAGE_LIMIT = 4;

    private final ChatMemory chatMemory;
    private final ConversationToolCallMemoryService toolCallMemoryService;

    public ConversationMemoryService(ChatMemory chatMemory) {
        this(chatMemory, null);
    }

    @Autowired
    public ConversationMemoryService(ChatMemory chatMemory, ConversationToolCallMemoryService toolCallMemoryService) {
        this.chatMemory = chatMemory;
        this.toolCallMemoryService = toolCallMemoryService;
    }

    /**
     * 将一轮用户输入和助手输出写入指定用户会话的短期记忆。
     */
    public void rememberTurn(String userId, String sessionId, String userText, String assistantText) {
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(userText)) {
            messages.add(new UserMessage(userText));
        }
        if (StringUtils.hasText(assistantText)) {
            messages.add(new AssistantMessage(assistantText));
        }
        if (!messages.isEmpty()) {
            chatMemory.add(buildConversationId(userId, sessionId), messages);
        }
    }

    /**
     * 读取指定用户会话的最近消息，用于轻量任务补充少量短期上下文。
     */
    public List<Message> recentMessages(String userId, String sessionId, int maxMessages) {
        if (maxMessages <= 0) {
            return List.of();
        }
        List<Message> messages = chatMemory.get(buildConversationId(userId, sessionId));
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, messages.size() - maxMessages);
        return List.copyOf(messages.subList(fromIndex, messages.size()));
    }

    /**
     * 读取最近 2 轮问答和最近工具调用结果，并渲染成可拼入 Agent 提示词的上下文片段。
     */
    public String recentConversationContext(String userId, String sessionId) {
        String conversationContext = recentShortTermConversationContext(userId, sessionId);
        String toolContext = recentToolCallContext(userId, sessionId);
        if (!StringUtils.hasText(conversationContext)) {
            return toolContext;
        }
        if (!StringUtils.hasText(toolContext)) {
            return conversationContext;
        }
        return conversationContext + System.lineSeparator() + System.lineSeparator() + toolContext;
    }

    public String recentToolCallContext(String userId, String sessionId) {
        if (toolCallMemoryService == null) {
            return "";
        }
        return toolCallMemoryService.recentToolCallContext(userId, sessionId);
    }

    /**
     * 生成 ChatMemory 使用的稳定会话键。
     */
    public String buildConversationId(String userId, String sessionId) {
        return normalizeUserId(userId) + "::" + normalizeSessionId(sessionId);
    }

    private String recentShortTermConversationContext(String userId, String sessionId) {
        List<Message> messages = recentMessages(userId, sessionId, RECENT_CONVERSATION_CONTEXT_MESSAGE_LIMIT);
        if (messages.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Message message : messages) {
            String text = messageText(message);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String role = messageRole(message);
            if (!StringUtils.hasText(role)) {
                continue;
            }
            lines.add(role + "：" + text.trim());
        }
        if (lines.isEmpty()) {
            return "";
        }
        return """
                最近 2 轮短期对话上下文：
                %s

                使用规则：
                - 本轮出现“那、这个、它、加 N 件”等省略指代时，优先结合最近上下文解析。
                - 如果最近上下文中没有唯一可确定商品，禁止猜 SKU 或订单信息，先要求用户确认。
                """.formatted(String.join(System.lineSeparator(), lines)).trim();
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : DEFAULT_USER_ID;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
    }

    private String messageRole(Message message) {
        if (message == null || message.getMessageType() == null) {
            return "";
        }
        MessageType type = message.getMessageType();
        if (type == MessageType.USER) {
            return "用户";
        }
        if (type == MessageType.ASSISTANT) {
            return "助手";
        }
        return "";
    }

    private String messageText(Message message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.getText();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.getText();
        }
        return "";
    }
}
