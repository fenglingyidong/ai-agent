package com.example.ragagent.memory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 在 ChatClient 调用前召回长期记忆，并追加到系统提示词中。
 */
@Component
public class LongTermMemoryAdvisor implements BaseAdvisor {

    public static final String USER_ID_KEY = "chat_memory_user_id";
    public static final String SESSION_ID_KEY = "chat_memory_session_id";

    private static final String DEFAULT_USER_ID = "anonymous";

    private final LongTermMemoryService longTermMemoryService;

    public LongTermMemoryAdvisor(LongTermMemoryService longTermMemoryService) {
        this.longTermMemoryService = longTermMemoryService;
    }

    /**
     * 在模型请求前注入与当前用户输入相关的长期记忆片段。
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String currentUserText = extractCurrentUserText(request.prompt());
        String longTermMemory = longTermMemoryService.retrieveLongTermMemory(resolveUserId(request.context()), currentUserText);
        if (!StringUtils.hasText(longTermMemory)) {
            return request;
        }

        String longTermMemoryPrompt = longTermMemoryService.renderLongTermMemoryPrompt(longTermMemory);
        Prompt updatedPrompt = request.prompt()
                .augmentSystemMessage(systemMessage -> new SystemMessage(appendSystemMessage(
                        systemMessage == null ? "" : systemMessage.getText(),
                        longTermMemoryPrompt
                )));
        return request.mutate().prompt(updatedPrompt).build();
    }

    /**
     * 模型响应后不做改写，仅保持 Advisor 生命周期完整。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse advisedResponse, AdvisorChain advisorChain) {
        return advisedResponse;
    }

    /**
     * 返回 Advisor 名称，便于日志和调试定位。
     */
    @Override
    public String getName() {
        return "longTermMemoryAdvisor";
    }

    /**
     * 让长期记忆在短期 ChatMemory 之前生效，避免被后续系统提示覆盖。
     */
    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 10;
    }

    private String extractCurrentUserText(Prompt prompt) {
        UserMessage userMessage = prompt.getUserMessage();
        return userMessage == null ? "" : userMessage.getText();
    }

    private String resolveUserId(Map<String, Object> context) {
        Object userId = context.get(USER_ID_KEY);
        return userId instanceof String id && StringUtils.hasText(id) ? id : DEFAULT_USER_ID;
    }

    private String appendSystemMessage(String currentSystemText, String extraSystemText) {
        if (!StringUtils.hasText(currentSystemText)) {
            return StringUtils.hasText(extraSystemText) ? extraSystemText.trim() : "";
        }
        if (!StringUtils.hasText(extraSystemText)) {
            return currentSystemText.trim();
        }
        return currentSystemText.trim()
                + System.lineSeparator()
                + System.lineSeparator()
                + extraSystemText.trim();
    }
}
