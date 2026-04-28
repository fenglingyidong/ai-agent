package com.example.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app.ai.chat-models")
public class ChatModelsProperties {

    private String defaultModel;

    private Map<String, ChatModelItem> items = new LinkedHashMap<>();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, ChatModelItem> getItems() {
        return items;
    }

    public void setItems(Map<String, ChatModelItem> items) {
        this.items = items == null ? new LinkedHashMap<>() : new LinkedHashMap<>(items);
    }

    public static class ChatModelItem {

        private String label;

        private String model;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String displayLabel(String modelId) {
            return StringUtils.hasText(label) ? label : modelId;
        }
    }
}
