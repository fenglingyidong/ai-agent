package com.example.ragagent.service;

import com.example.ragagent.config.ChatModelsProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChatModelRegistry {

    private final ChatModelsProperties properties;

    public ChatModelRegistry(ChatModelsProperties properties) {
        this.properties = properties;
    }

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

    public String resolveModelName(String requestedModelId) {
        String resolvedModelId = resolveModelId(requestedModelId);
        if (!StringUtils.hasText(resolvedModelId)) {
            return null;
        }
        ChatModelsProperties.ChatModelItem item = properties.getItems().get(resolvedModelId);
        return item == null ? null : item.getModel();
    }

    public OpenAiChatOptions createOptions(String requestedModelId) {
        String modelName = resolveModelName(requestedModelId);
        return StringUtils.hasText(modelName)
                ? OpenAiChatOptions.builder().model(modelName).build()
                : null;
    }

    public String getDefaultModelId() {
        return resolveModelId(properties.getDefaultModel());
    }

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

    public record AvailableChatModel(
            String id,
            String label,
            String model
    ) {
    }
}
