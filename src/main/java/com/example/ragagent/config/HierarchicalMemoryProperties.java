package com.example.ragagent.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 分层记忆配置，控制短期窗口、长期召回和摘要线程池参数。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.memory")
public class HierarchicalMemoryProperties {

    @Min(1)
    private int maxRecentMessages = 12;

    @NotNull
    private Duration maxRecentAge = Duration.ofMinutes(30);

    @NotNull
    private Duration shortTermTtl = Duration.ofDays(30);

    @NotNull
    private Duration idleSummaryAge = Duration.ofDays(1);

    @NotNull
    private Duration idleSummaryScanInterval = Duration.ofMinutes(15);

    @Min(0)
    private int longTermTopK = 3;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double longTermSimilarityThreshold = 0.0d;

    @Min(1)
    private int summarizationConcurrency = 2;

    @Min(1)
    private int threadQueueCapacity = 100;
}
