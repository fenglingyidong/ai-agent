package com.example.ragagent.memory;

import org.springframework.util.StringUtils;

/**
 * 会话内一次工具调用的安全记录，不包含调用发生时间字段。
 */
public record ConversationToolCallRecord(
        String toolName,
        String input,
        String output,
        Status status,
        String errorType
) {

    public ConversationToolCallRecord {
        toolName = StringUtils.hasText(toolName) ? toolName.trim() : "<unknown>";
        input = input == null ? "" : input;
        output = output == null ? "" : output;
        status = status == null ? Status.OK : status;
        errorType = errorType == null ? "" : errorType;
    }

    public enum Status {
        OK,
        ERROR
    }
}
