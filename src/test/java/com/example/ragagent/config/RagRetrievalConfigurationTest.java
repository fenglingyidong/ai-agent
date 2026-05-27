package com.example.ragagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagRetrievalConfigurationTest {

    @Test
    void ragRetrievalExecutorShouldUseConfiguredPoolSettings() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setParallelExecutorCoreSize(2);
        properties.setParallelExecutorMaxSize(4);
        properties.setParallelExecutorQueueCapacity(16);

        ThreadPoolTaskExecutor executor = new RagRetrievalConfiguration()
                .ragRetrievalExecutor(properties);
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(4, executor.getMaxPoolSize());
            assertEquals(16, executor.getQueueCapacity());
            assertEquals("rag-retrieval-", executor.getThreadNamePrefix());
        }
        finally {
            executor.shutdown();
        }
    }
}
