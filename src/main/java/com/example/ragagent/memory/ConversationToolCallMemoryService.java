package com.example.ragagent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final int MAX_RECORD_WINDOW_SIZE = 20;
    private static final int FULL_RESULT_WINDOW_SIZE = 3;
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final int MAX_TOOL_NAME_LENGTH = 120;
    private static final String FOLDED_INPUT = "完整结果已折叠；如需精确事实请重新调用工具。";
    private static final String FOLDED_OUTPUT = "完整结果已折叠；如需精确事实请重新调用工具。";
    private static final String FOLDED_HISTORY_TOOL_NAME = "历史工具调用";
    private static final String SENSITIVE_FIELD_NAME =
            "token|authorization|password|mallToken|mallPassword|mallUsername|accessToken|access_token"
                    + "|access-token|refreshToken|refresh_token|refresh-token|authToken|auth_token|auth-token"
                    + "|apiKey|api_key|api-key|x-api-key|clientSecret|client_secret|client-secret";
    private static final Set<String> SENSITIVE_JSON_FIELD_NAMES = Set.of(
            "token",
            "authorization",
            "password",
            "malltoken",
            "mallpassword",
            "mallusername",
            "accesstoken",
            "access_token",
            "access-token",
            "refreshtoken",
            "refresh_token",
            "refresh-token",
            "authtoken",
            "auth_token",
            "auth-token",
            "apikey",
            "api_key",
            "api-key",
            "x-api-key",
            "clientsecret",
            "client_secret",
            "client-secret"
    );
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:" + SENSITIVE_FIELD_NAME + ")\"\\s*:\\s*\")([^\"]*)(\")"
    );
    private static final Pattern SENSITIVE_TEXT_FIELD = Pattern.compile(
            "(?i)\\b(" + SENSITIVE_FIELD_NAME + ")(\\s*[:=]\\s*)(.*?)(?=(?:\\s+"
                    + "[A-Za-z][A-Za-z0-9_\\-]*\\s*[:=])|[,;\\r\\n]|$)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._+\\-/:=]+");
    private static final Pattern FOLDED_HISTORY_COUNT = Pattern.compile("^(\\d+) 条更早工具调用已折叠");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConcurrentMap<ConversationId, List<ConversationToolCallRecord>> recordsByConversationId =
            new ConcurrentHashMap<>();

    public void rememberSuccess(String userId,
                                String sessionId,
                                String toolName,
                                String input,
                                String output) {
        append(userId, sessionId, new ConversationToolCallRecord(
                sanitizeToolName(toolName),
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
                sanitizeToolName(toolName),
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
            if (isFoldedHistoryRecord(record)) {
                builder.append(record.input());
                continue;
            }
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
            boundRecords(records);
            foldOlderRecords(records);
        }
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        String sanitized = sanitizeJson(trimmed);
        if (sanitized == null) {
            sanitized = SENSITIVE_JSON_FIELD.matcher(trimmed).replaceAll("$1[REDACTED]$3");
        }
        sanitized = SENSITIVE_TEXT_FIELD.matcher(sanitized).replaceAll("$1$2[REDACTED]");
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        if (sanitized.length() <= MAX_TEXT_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_TEXT_LENGTH) + System.lineSeparator() + "内容已截断。";
    }

    private String sanitizeToolName(String toolName) {
        String sanitized = sanitize(toolName);
        if (sanitized.length() <= MAX_TOOL_NAME_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_TOOL_NAME_LENGTH - 3) + "...";
    }

    private String sanitizeJson(String value) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value);
            redactSensitiveJsonFields(root);
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private void redactSensitiveJsonFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveFieldName(field.getKey())) {
                    objectNode.set(field.getKey(), TextNode.valueOf("[REDACTED]"));
                } else if (field.getValue().isTextual()) {
                    objectNode.set(field.getKey(), TextNode.valueOf(sanitizeBearerToken(field.getValue().asText())));
                } else {
                    redactSensitiveJsonFields(field.getValue());
                }
            }
            return;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int index = 0; index < arrayNode.size(); index++) {
                JsonNode item = arrayNode.get(index);
                if (item.isTextual()) {
                    arrayNode.set(index, TextNode.valueOf(sanitizeBearerToken(item.asText())));
                } else {
                    redactSensitiveJsonFields(item);
                }
            }
        }
    }

    private String sanitizeBearerToken(String value) {
        return BEARER_TOKEN.matcher(value).replaceAll("Bearer [REDACTED]");
    }

    private boolean isSensitiveFieldName(String fieldName) {
        return fieldName != null
                && SENSITIVE_JSON_FIELD_NAMES.contains(fieldName.toLowerCase(Locale.ROOT));
    }

    private void boundRecords(List<ConversationToolCallRecord> records) {
        if (records.size() <= MAX_RECORD_WINDOW_SIZE) {
            return;
        }
        int removeCount = records.size() - MAX_RECORD_WINDOW_SIZE + 1;
        int foldedCount = removeCount;
        if (isFoldedHistoryRecord(records.get(0))) {
            foldedCount = foldedHistoryCount(records.get(0)) + removeCount - 1;
        }
        records.subList(0, removeCount).clear();
        records.add(0, foldedHistoryRecord(foldedCount));
    }

    private ConversationToolCallRecord foldedHistoryRecord(int count) {
        String foldedText = count + " 条更早工具调用已折叠；如需精确事实请重新调用工具。";
        return new ConversationToolCallRecord(
                FOLDED_HISTORY_TOOL_NAME,
                foldedText,
                foldedText,
                ConversationToolCallRecord.Status.OK,
                "",
                true
        );
    }

    private boolean isFoldedHistoryRecord(ConversationToolCallRecord record) {
        return record.foldedHistory();
    }

    private int foldedHistoryCount(ConversationToolCallRecord record) {
        java.util.regex.Matcher matcher = FOLDED_HISTORY_COUNT.matcher(record.input());
        if (!matcher.find()) {
            return 0;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private void foldOlderRecords(List<ConversationToolCallRecord> records) {
        int fullFromIndex = Math.max(0, records.size() - FULL_RESULT_WINDOW_SIZE);
        for (int index = 0; index < fullFromIndex; index++) {
            ConversationToolCallRecord record = records.get(index);
            if (isFoldedHistoryRecord(record)
                    || (FOLDED_INPUT.equals(record.input()) && FOLDED_OUTPUT.equals(record.output()))) {
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

    private ConversationId buildConversationId(String userId, String sessionId) {
        return new ConversationId(normalizeUserId(userId), normalizeSessionId(sessionId));
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId.trim() : DEFAULT_USER_ID;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : DEFAULT_SESSION_ID;
    }

    private record ConversationId(String userId, String sessionId) {
    }
}
