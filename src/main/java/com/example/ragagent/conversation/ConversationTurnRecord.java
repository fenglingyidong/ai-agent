package com.example.ragagent.conversation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一轮对话的持久化视图，覆盖请求参数、输出状态、错误和时间戳。
 */
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

    /**
     * 规范化可空字段，避免数据库和 API 层处理 null。
     */
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

    /**
     * 创建一条刚开始处理的对话轮次。
     */
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

    /**
     * 返回已完成状态的新轮次记录。
     */
    public ConversationTurnRecord complete(String assistantText, long completedAtEpochMillis) {
        return withStatus(ConversationTurnStatus.COMPLETED, assistantText, "", completedAtEpochMillis);
    }

    /**
     * 返回部分完成状态的新轮次记录。
     */
    public ConversationTurnRecord partial(String assistantText, String errorMessage, long completedAtEpochMillis) {
        return withStatus(ConversationTurnStatus.PARTIAL, assistantText, errorMessage, completedAtEpochMillis);
    }

    /**
     * 返回失败状态的新轮次记录。
     */
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
