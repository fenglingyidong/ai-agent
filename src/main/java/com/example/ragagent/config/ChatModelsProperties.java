package com.example.ragagent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.ai.chat-models")
public class ChatModelsProperties {

    private String defaultModel;

    @Valid
    @NotEmpty
    private Map<String, ChatModelItem> items = new LinkedHashMap<>();

    public void setItems(Map<String, ChatModelItem> items) {
        this.items = items == null ? new LinkedHashMap<>() : new LinkedHashMap<>(items);
    }

    @Getter
    @Setter
    public static class ChatModelItem {

        private String label;

        @NotBlank
        private String model;

        public String displayLabel(String modelId) {
            return StringUtils.hasText(label) ? label : modelId;
        }
    }
}
