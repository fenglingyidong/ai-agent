package com.example.ragagent.service;

import com.example.ragagent.observability.RagTracing;
import io.opentelemetry.api.trace.Span;
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
    private final RagTracing tracing;

    LoggingToolCallback(ToolCallback delegate,
                        String userId,
                        String sessionId,
                        RagTracing tracing) {
        this.delegate = delegate;
        this.userId = userId;
        this.sessionId = sessionId;
        this.tracing = tracing == null ? new RagTracing() : tracing;
    }

    LoggingToolCallback(ToolCallback delegate,
                        String userId,
                        String sessionId) {
        this(delegate, userId, sessionId, new RagTracing());
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
        RuntimeException[] toolError = new RuntimeException[1];
        String result = tracing.inSpan(toolSpanName(), () -> {
            Span span = tracing.currentSpan();
            writeToolStartAttributes(span, input);
            logToolInput(input);
            try {
                String delegateResult = delegate.call(input);
                tracing.setAttribute(span, "tool.output.length", textLength(delegateResult));
                tracing.setAttribute(span, "tool.status", "ok");
                logToolOutput(delegateResult);
                return delegateResult;
            }
            catch (RuntimeException ex) {
                writeToolErrorAttributes(span, ex);
                logToolError(ex);
                toolError[0] = ex;
                return null;
            }
        });
        if (toolError[0] != null) {
            throw toolError[0];
        }
        return result;
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        RuntimeException[] toolError = new RuntimeException[1];
        String result = tracing.inSpan(toolSpanName(), () -> {
            Span span = tracing.currentSpan();
            writeToolStartAttributes(span, input);
            logToolInput(input);
            try {
                String delegateResult = delegate.call(input, toolContext);
                tracing.setAttribute(span, "tool.output.length", textLength(delegateResult));
                tracing.setAttribute(span, "tool.status", "ok");
                logToolOutput(delegateResult);
                return delegateResult;
            }
            catch (RuntimeException ex) {
                writeToolErrorAttributes(span, ex);
                logToolError(ex);
                toolError[0] = ex;
                return null;
            }
        });
        if (toolError[0] != null) {
            throw toolError[0];
        }
        return result;
    }

    private void writeToolStartAttributes(Span span, String input) {
        tracing.setAttribute(span, "tool.name", toolName());
        tracing.setAttribute(span, "tool.input.length", textLength(input));
        tracing.setAttribute(span, "app.user_id", userId);
        tracing.setAttribute(span, "app.session_id", sessionId);
    }

    private void writeToolErrorAttributes(Span span, RuntimeException ex) {
        String errorType = ex.getClass().getSimpleName();
        tracing.setAttribute(span, "tool.status", "error");
        tracing.setAttribute(span, "tool.error.type", errorType);
        tracing.setAttribute(span, "error.type", errorType);
    }

    private String toolSpanName() {
        String name = toolName();
        return name == null || name.isBlank() || "<unknown>".equals(name) ? "tool.unknown" : "tool." + name;
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
