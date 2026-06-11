package com.example.ragagent.conversation;

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
