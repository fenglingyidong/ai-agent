package com.example.ragagent.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
    private static final int RECENT_MESSAGE_LIMIT = 4;

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
     * 生成 ChatMemory 使用的稳定会话键。
     */
    public String buildConversationId(String userId, String sessionId) {
        return normalizeUserId(userId) + "::" + normalizeSessionId(sessionId);
    }

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

    private String recentShortTermConversationContext(String userId, String sessionId) {
        List<Message> messages = chatMemory.get(buildConversationId(userId, sessionId));
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<Message> recentMessages = messages.subList(Math.max(0, messages.size() - RECENT_MESSAGE_LIMIT), messages.size());
        StringBuilder builder = new StringBuilder("最近 2 轮短期对话上下文：");
        for (Message message : recentMessages) {
            String renderedMessage = renderMessage(message);
            if (StringUtils.hasText(renderedMessage)) {
                builder.append(System.lineSeparator()).append(renderedMessage);
            }
        }
        return builder.length() == "最近 2 轮短期对话上下文：".length() ? "" : builder.toString();
    }

    private String renderMessage(Message message) {
        if (message instanceof UserMessage userMessage) {
            return "用户：" + userMessage.getText();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return "助手：" + assistantMessage.getText();
        }
        return "";
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : DEFAULT_USER_ID;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
    }
}
