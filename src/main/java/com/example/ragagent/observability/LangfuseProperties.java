package com.example.ragagent.observability;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 可观测性配置，控制是否启用以及是否采集提示词、工具和 RAG 内容。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.observability.langfuse")
public class LangfuseProperties {

    private boolean enabled = false;

    private String baseUrl = "http://localhost:3001";

    private boolean capturePrompt = false;

    private boolean captureToolPayload = false;

    private boolean captureRagContent = false;

    private int maxCaptureChars = 8_000;
}
