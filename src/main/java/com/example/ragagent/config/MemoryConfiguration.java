package com.example.ragagent.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 分层记忆相关 Bean 配置，包含短期 ChatMemory、Advisor 和摘要线程池。
 */
@Configuration
@EnableScheduling
public class MemoryConfiguration {

    /**
     * 创建基于窗口大小的短期聊天记忆。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, HierarchicalMemoryProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(properties.getMaxRecentMessages())
                .build();
    }

    /**
     * 创建 Spring AI 的短期记忆 Advisor。
     */
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    /**
     * 暴露空闲摘要扫描间隔，供 @Scheduled 表达式引用。
     */
    @Bean
    public Long memoryIdleSummaryScanDelayMillis(HierarchicalMemoryProperties properties) {
        return properties.getIdleSummaryScanInterval().toMillis();
    }

    /**
     * 创建长期记忆摘要使用的后台线程池。
     */
    @Bean
    @Qualifier("hierarchicalMemoryExecutor")
    public Executor hierarchicalMemoryExecutor(HierarchicalMemoryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("memory-summary-");
        executor.setCorePoolSize(properties.getSummarizationConcurrency());
        executor.setMaxPoolSize(properties.getSummarizationConcurrency());
        executor.setQueueCapacity(properties.getThreadQueueCapacity());
        executor.initialize();
        return executor;
    }
}
