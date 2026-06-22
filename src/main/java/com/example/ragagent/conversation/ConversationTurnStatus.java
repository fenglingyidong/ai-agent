package com.example.ragagent.conversation;

/**
 * 对话轮次的处理状态，用于区分流式完成、失败和部分输出。
 */
public enum ConversationTurnStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    PARTIAL
}
