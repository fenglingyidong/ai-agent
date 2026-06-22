package com.example.ragagent.service;

import com.example.ragagent.config.ChatModelsProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理可选聊天模型配置，并为请求解析最终使用的模型参数。
 */
@Component
public class ChatModelRegistry {

    @Autowired
    private ChatModelsProperties properties;

    public ChatModelRegistry() {
    }

    public ChatModelRegistry(ChatModelsProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据请求模型、默认模型和配置顺序解析可用的模型 id。
     */
    public String resolveModelId(String requestedModelId) {
        Map<String, ChatModelsProperties.ChatModelItem> items = properties.getItems();
        if (items.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(requestedModelId) && items.containsKey(requestedModelId)) {
            return requestedModelId;
        }
        if (StringUtils.hasText(properties.getDefaultModel()) && items.containsKey(properties.getDefaultModel())) {
            return properties.getDefaultModel();
        }
        return items.keySet().iterator().next();
    }

    /**
     * 将模型 id 解析为底层 OpenAI 兼容接口使用的模型名称。
     */
    public String resolveModelName(String requestedModelId) {
        String resolvedModelId = resolveModelId(requestedModelId);
        if (!StringUtils.hasText(resolvedModelId)) {
            return null;
        }
        ChatModelsProperties.ChatModelItem item = properties.getItems().get(resolvedModelId);
        return item == null ? null : item.getModel();
    }

    /**
     * 为指定模型 id 创建 Spring AI 聊天选项。
     */
    public OpenAiChatOptions createOptions(String requestedModelId) {
        String modelName = resolveModelName(requestedModelId);
        return StringUtils.hasText(modelName)
                ? OpenAiChatOptions.builder().model(modelName).build()
                : null;
    }

    /**
     * 返回当前对外展示的默认模型 id。
     */
    public String getDefaultModelId() {
        return resolveModelId(properties.getDefaultModel());
    }

    /**
     * 列出前端可选择的聊天模型及其显示标签。
     */
    public List<AvailableChatModel> listAvailableModels() {
        Map<String, ChatModelsProperties.ChatModelItem> items = new LinkedHashMap<>(properties.getItems());
        return items.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getValue().getModel()))
                .map(entry -> new AvailableChatModel(
                        entry.getKey(),
                        entry.getValue().displayLabel(entry.getKey()),
                        entry.getValue().getModel()
                ))
                .toList();
    }

    /**
     * 前端模型选择器展示的模型条目。
     */
    public record AvailableChatModel(
            String id,
            String label,
            String model
    ) {
    }
}
