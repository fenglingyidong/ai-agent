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

/**
 * 聊天模型配置，定义默认模型和前端可选择的模型清单。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.ai.chat-models")
public class ChatModelsProperties {

    private String defaultModel;

    @Valid
    @NotEmpty
    private Map<String, ChatModelItem> items = new LinkedHashMap<>();

    /**
     * 复制配置项，避免外部 Map 后续修改影响运行时模型列表。
     */
    public void setItems(Map<String, ChatModelItem> items) {
        this.items = items == null ? new LinkedHashMap<>() : new LinkedHashMap<>(items);
    }

    /**
     * 单个可选聊天模型的展示名和实际模型名。
     */
    @Getter
    @Setter
    public static class ChatModelItem {

        private String label;

        @NotBlank
        private String model;

        /**
         * 优先使用配置展示名；未配置时回退到模型 ID。
         */
        public String displayLabel(String modelId) {
            return StringUtils.hasText(label) ? label : modelId;
        }
    }
}
