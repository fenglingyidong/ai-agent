package com.example.ragagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

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
        log.info("ReAct tool start: userId={}, sessionId={}, toolName={}, inputLength={}",
                userId,
                sessionId,
                toolName(),
                textLength(input));
    }

    private void logToolOutput(String result) {
        log.info("ReAct tool finish: userId={}, sessionId={}, toolName={}, outputLength={}",
                userId,
                sessionId,
                toolName(),
                textLength(result));
    }

    private void logToolError(RuntimeException ex) {
        log.warn("ReAct tool error: userId={}, sessionId={}, toolName={}, errorType={}",
                userId,
                sessionId,
                toolName(),
                ex.getClass().getSimpleName());
    }

    private String toolName() {
        ToolDefinition definition = getToolDefinition();
        return definition == null ? "<unknown>" : definition.name();
    }

    private int textLength(String text) {
        return text == null ? 0 : text.length();
    }
}
