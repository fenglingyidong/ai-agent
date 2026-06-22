package com.example.ragagent.conversation;

/**
 * 历史会话列表使用的摘要信息。
 */
public record ConversationSessionSummary(
        String sessionId,
        String title,
        long createdAt,
        long updatedAt,
        long turnCount,
        String latestUserText,
        String latestAssistantText
) {
}
