package com.example.ragagent.conversation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConversationTurnRecord(
        String id,
        String userId,
        String sessionId,
        long turnNo,
        String modelId,
        boolean webSearchEnabled,
        String userText,
        String assistantText,
        ConversationTurnStatus status,
        String errorMessage,
        long createdAtEpochMillis,
        long completedAtEpochMillis,
        Map<String, Object> metadata
) {

    public ConversationTurnRecord {
        id = nullToEmpty(id);
        userId = nullToEmpty(userId);
        sessionId = nullToEmpty(sessionId);
        modelId = nullToEmpty(modelId);
        userText = nullToEmpty(userText);
        assistantText = nullToEmpty(assistantText);
        status = status == null ? ConversationTurnStatus.PROCESSING : status;
        errorMessage = nullToEmpty(errorMessage);
        metadata = metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static ConversationTurnRecord started(String id,
                                                 String userId,
                                                 String sessionId,
                                                 long turnNo,
                                                 String modelId,
                                                 boolean webSearchEnabled,
                                                 String userText,
                                                 int mediaCount,
                                                 long createdAtEpochMillis) {
        return new ConversationTurnRecord(
                id,
                userId,
                sessionId,
                turnNo,
                modelId,
                webSearchEnabled,
                userText,
                "",
                ConversationTurnStatus.PROCESSING,
                "",
                createdAtEpochMillis,
                0L,
                Map.of("mediaCount", mediaCount)
        );
    }

    public ConversationTurnRecord complete(String assistantText, long completedAtEpochMillis) {
        return withStatus(ConversationTurnStatus.COMPLETED, assistantText, "", completedAtEpochMillis);
    }

    public ConversationTurnRecord partial(String assistantText, String errorMessage, long completedAtEpochMillis) {
        return withStatus(ConversationTurnStatus.PARTIAL, assistantText, errorMessage, completedAtEpochMillis);
    }

    public ConversationTurnRecord fail(String assistantText, String errorMessage, long completedAtEpochMillis) {
        return withStatus(ConversationTurnStatus.FAILED, assistantText, errorMessage, completedAtEpochMillis);
    }

    private ConversationTurnRecord withStatus(ConversationTurnStatus nextStatus,
                                              String nextAssistantText,
                                              String nextErrorMessage,
                                              long nextCompletedAtEpochMillis) {
        return new ConversationTurnRecord(
                id,
                userId,
                sessionId,
                turnNo,
                modelId,
                webSearchEnabled,
                userText,
                nextAssistantText,
                nextStatus,
                nextErrorMessage,
                createdAtEpochMillis,
                nextCompletedAtEpochMillis,
                metadata
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
