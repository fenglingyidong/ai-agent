package com.example.ragagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发短期记忆空闲摘要扫描，避免只在窗口淘汰时才生成长期记忆。
 */
@Component
public class ShortTermMemorySummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShortTermMemorySummaryScheduler.class);

    private final RedisChatMemoryRepository chatMemoryRepository;

    public ShortTermMemorySummaryScheduler(RedisChatMemoryRepository chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /**
     * 按配置间隔扫描空闲短期会话，并调度长期摘要任务。
     */
    @Scheduled(fixedDelayString = "#{@memoryIdleSummaryScanDelayMillis}")
    public void summarizeIdleShortTermMemory() {
        try {
            int scheduledCount = chatMemoryRepository.summarizeIdleConversations();
            if (scheduledCount > 0) {
                log.info("Scheduled idle short-term memory summaries: {}", scheduledCount);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Failed to scan idle short-term memory conversations", ex);
        }
    }
}
