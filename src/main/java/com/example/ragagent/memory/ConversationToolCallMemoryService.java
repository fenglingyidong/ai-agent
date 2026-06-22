package com.example.ragagent.memory;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 维护会话级工具调用上下文窗口，按追加顺序保留记录。
 */
@Service
public class ConversationToolCallMemoryService {

    private static final String DEFAULT_USER_ID = "anonymous";
    private static final String DEFAULT_SESSION_ID = "default";
    private static final int FULL_RESULT_WINDOW_SIZE = 3;
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final String FOLDED_INPUT = "完整结果已折叠；如需精确事实请重新调用工具。";
    private static final String FOLDED_OUTPUT = "完整结果已折叠；如需精确事实请重新调用工具。";
    private static final String SENSITIVE_FIELD_NAME =
            "token|authorization|password|mallToken|mallPassword|mallUsername";
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:" + SENSITIVE_FIELD_NAME + ")\"\\s*:\\s*\")([^\"]*)(\")"
    );
    private static final Pattern SENSITIVE_TEXT_FIELD = Pattern.compile(
            "(?i)\\b(" + SENSITIVE_FIELD_NAME + ")(\\s*[:=]\\s*)(.*?)(?=(?:\\s+\\b(?:"
                    + SENSITIVE_FIELD_NAME + ")\\s*[:=])|[,;\\r\\n]|$)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+\\S+");

    private final ConcurrentMap<String, List<ConversationToolCallRecord>> recordsByConversationId =
            new ConcurrentHashMap<>();

    public void rememberSuccess(String userId,
                                String sessionId,
                                String toolName,
                                String input,
                                String output) {
        append(userId, sessionId, new ConversationToolCallRecord(
                toolName,
                sanitize(input),
                sanitize(output),
                ConversationToolCallRecord.Status.OK,
                ""
        ));
    }

    public void rememberError(String userId,
                              String sessionId,
                              String toolName,
                              String input,
                              RuntimeException ex) {
        append(userId, sessionId, new ConversationToolCallRecord(
                toolName,
                sanitize(input),
                "工具调用失败，完整错误已省略。",
                ConversationToolCallRecord.Status.ERROR,
                ex == null ? "RuntimeException" : ex.getClass().getSimpleName()
        ));
    }

    public String recentToolCallContext(String userId, String sessionId) {
        List<ConversationToolCallRecord> records = records(userId, sessionId);
        if (records.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("最近工具调用上下文：");
        int fullFromIndex = Math.max(0, records.size() - FULL_RESULT_WINDOW_SIZE);
        for (int index = 0; index < records.size(); index++) {
            ConversationToolCallRecord record = records.get(index);
            builder.append(System.lineSeparator());
            builder.append("[工具调用 ").append(index + 1).append("] ");
            if (index < fullFromIndex) {
                builder.append("已调用 ").append(record.toolName())
                        .append("，完整结果已折叠；如需精确事实请重新调用工具。");
                continue;
            }
            builder.append(record.toolName())
                    .append(System.lineSeparator())
                    .append("状态：").append(record.status())
                    .append(System.lineSeparator())
                    .append("输入：").append(record.input())
                    .append(System.lineSeparator());
            if (record.status() == ConversationToolCallRecord.Status.ERROR) {
                builder.append("错误类型：").append(record.errorType())
                        .append(System.lineSeparator());
            }
            builder.append("结果：").append(record.output());
        }
        return builder.toString();
    }

    public List<ConversationToolCallRecord> records(String userId, String sessionId) {
        List<ConversationToolCallRecord> records = recordsByConversationId.get(buildConversationId(userId, sessionId));
        if (records == null) {
            return List.of();
        }
        synchronized (records) {
            if (records.isEmpty()) {
                return List.of();
            }
            return List.copyOf(records);
        }
    }

    private void append(String userId, String sessionId, ConversationToolCallRecord record) {
        List<ConversationToolCallRecord> records = recordsByConversationId.computeIfAbsent(
                buildConversationId(userId, sessionId),
                ignored -> new ArrayList<>()
        );
        synchronized (records) {
            records.add(record);
            foldOlderRecords(records);
        }
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String sanitized = SENSITIVE_JSON_FIELD.matcher(value.trim()).replaceAll("$1[REDACTED]$3");
        sanitized = SENSITIVE_TEXT_FIELD.matcher(sanitized).replaceAll("$1$2[REDACTED]");
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        if (sanitized.length() <= MAX_TEXT_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_TEXT_LENGTH) + System.lineSeparator() + "内容已截断。";
    }

    private void foldOlderRecords(List<ConversationToolCallRecord> records) {
        int fullFromIndex = Math.max(0, records.size() - FULL_RESULT_WINDOW_SIZE);
        for (int index = 0; index < fullFromIndex; index++) {
            ConversationToolCallRecord record = records.get(index);
            if (FOLDED_INPUT.equals(record.input()) && FOLDED_OUTPUT.equals(record.output())) {
                continue;
            }
            records.set(index, new ConversationToolCallRecord(
                    record.toolName(),
                    FOLDED_INPUT,
                    FOLDED_OUTPUT,
                    record.status(),
                    record.errorType()
            ));
        }
    }

    private String buildConversationId(String userId, String sessionId) {
        return normalizeUserId(userId) + "::" + normalizeSessionId(sessionId);
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId.trim() : DEFAULT_USER_ID;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : DEFAULT_SESSION_ID;
    }
}
