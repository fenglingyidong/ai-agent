package com.example.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.memory")
public class HierarchicalMemoryProperties {

    private int maxRecentMessages = 12;

    private Duration maxRecentAge = Duration.ofMinutes(30);

    private int longTermTopK = 3;

    private double longTermSimilarityThreshold = 0.0d;

    private int summarizationConcurrency = 2;

    private int threadQueueCapacity = 100;

    public int getMaxRecentMessages() {
        return maxRecentMessages;
    }

    public void setMaxRecentMessages(int maxRecentMessages) {
        this.maxRecentMessages = maxRecentMessages;
    }

    public Duration getMaxRecentAge() {
        return maxRecentAge;
    }

    public void setMaxRecentAge(Duration maxRecentAge) {
        this.maxRecentAge = maxRecentAge;
    }

    public int getLongTermTopK() {
        return longTermTopK;
    }

    public void setLongTermTopK(int longTermTopK) {
        this.longTermTopK = longTermTopK;
    }

    public double getLongTermSimilarityThreshold() {
        return longTermSimilarityThreshold;
    }

    public void setLongTermSimilarityThreshold(double longTermSimilarityThreshold) {
        this.longTermSimilarityThreshold = longTermSimilarityThreshold;
    }

    public int getSummarizationConcurrency() {
        return summarizationConcurrency;
    }

    public void setSummarizationConcurrency(int summarizationConcurrency) {
        this.summarizationConcurrency = summarizationConcurrency;
    }

    public int getThreadQueueCapacity() {
        return threadQueueCapacity;
    }

    public void setThreadQueueCapacity(int threadQueueCapacity) {
        this.threadQueueCapacity = threadQueueCapacity;
    }
}
