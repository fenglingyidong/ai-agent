package com.example.ragagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RagRetrievalConfiguration {

    public static final String RAG_RETRIEVAL_EXECUTOR = "ragRetrievalExecutor";

    @Bean(name = RAG_RETRIEVAL_EXECUTOR)
    public ThreadPoolTaskExecutor ragRetrievalExecutor(RagRetrievalProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getParallelExecutorCoreSize());
        executor.setMaxPoolSize(properties.getParallelExecutorMaxSize());
        executor.setQueueCapacity(properties.getParallelExecutorQueueCapacity());
        executor.setThreadNamePrefix("rag-retrieval-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
