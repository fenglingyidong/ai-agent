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

@Configuration
@EnableScheduling
public class MemoryConfiguration {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, HierarchicalMemoryProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(properties.getMaxRecentMessages())
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    public Long memoryIdleSummaryScanDelayMillis(HierarchicalMemoryProperties properties) {
        return properties.getIdleSummaryScanInterval().toMillis();
    }

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
