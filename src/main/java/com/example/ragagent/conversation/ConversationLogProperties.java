package com.example.ragagent.conversation;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 会话日志持久化配置，控制是否启用和每个会话保留的最大轮次数。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.conversation-log")
public class ConversationLogProperties {

    private boolean enabled = true;

    @Min(1)
    private int maxTurnsPerSession = 1000;
}
