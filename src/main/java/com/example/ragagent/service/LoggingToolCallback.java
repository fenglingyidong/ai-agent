package com.example.ragagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

final class LoggingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

    private final ToolCallback delegate;
    private final String userId;
    private final String sessionId;

    LoggingToolCallback(ToolCallback delegate,
                        String userId,
                        String sessionId) {
        this.delegate = delegate;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        logToolInput(input);
        try {
            String result = delegate.call(input);
            logToolOutput(result);
            return result;
        }
        catch (RuntimeException ex) {
            logToolError(ex);
            throw ex;
        }
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        logToolInput(input);
        try {
            String result = delegate.call(input, toolContext);
            logToolOutput(result);
            return result;
        }
        catch (RuntimeException ex) {
            logToolError(ex);
            throw ex;
        }
    }

    private void logToolInput(String input) {
        log.info("""
                ReAct 工具调用开始：
                userId={}
                sessionId={}
                toolName={}
                toolInput={}
                """,
                userId,
                sessionId,
                getToolDefinition().name(),
                StringUtils.hasText(input) ? input : "<empty>");
    }

    private void logToolOutput(String result) {
        log.info("""
                ReAct 工具调用完成：
                userId={}
                sessionId={}
                toolName={}
                toolOutput={}
                """,
                userId,
                sessionId,
                getToolDefinition().name(),
                StringUtils.hasText(result) ? result : "<empty>");
    }

    private void logToolError(RuntimeException ex) {
        log.warn("""
                ReAct 工具调用失败：
                userId={}
                sessionId={}
                toolName={}
                error={}
                """,
                userId,
                sessionId,
                getToolDefinition().name(),
                ex.getMessage(),
                ex);
    }
}
