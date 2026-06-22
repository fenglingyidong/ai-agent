package com.example.ragagent.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

    private final ChatMemory chatMemory;

    public ConversationMemoryService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
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

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : DEFAULT_USER_ID;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
    }
}
