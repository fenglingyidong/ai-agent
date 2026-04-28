package com.example.ragagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class MemoryConfiguration {

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
