package com.example.ragagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * RAG 检索并行执行器配置。
 */
@Configuration
public class RagRetrievalConfiguration {

    public static final String RAG_RETRIEVAL_EXECUTOR = "ragRetrievalExecutor";

    /**
     * 创建 dense 与 BM25 混合检索使用的线程池。
     */
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
