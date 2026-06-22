package com.example.ragagent.service;

import com.example.ragagent.memory.ConversationToolCallMemoryService;
import com.example.ragagent.observability.RagTracing;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

/**
 * 为工具调用增加日志、OpenTelemetry span 和 Langfuse 工具输入输出采集。
 */
final class LoggingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

    private final ToolCallback delegate;
    private final String userId;
    private final String sessionId;
    private final RagTracing tracing;
    private final ConversationToolCallMemoryService toolCallMemoryService;

    LoggingToolCallback(ToolCallback delegate,
                        String userId,
                        String sessionId,
                        RagTracing tracing) {
        this(delegate, userId, sessionId, tracing, null);
    }

    LoggingToolCallback(ToolCallback delegate,
                        String userId,
                        String sessionId,
                        RagTracing tracing,
                        ConversationToolCallMemoryService toolCallMemoryService) {
        this.delegate = delegate;
        this.userId = userId;
        this.sessionId = sessionId;
        this.tracing = tracing == null ? new RagTracing() : tracing;
        this.toolCallMemoryService = toolCallMemoryService;
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

    /**
     * 调用无上下文工具，并记录本次工具执行的输入、输出和错误状态。
     */
    @Override
    public String call(String input) {
        RuntimeException[] toolError = new RuntimeException[1];
        String result = tracing.inSpan(toolSpanName(), () -> {
            Span span = tracing.currentSpan();
            writeToolStartAttributes(span, input, userId, sessionId);
            tracing.captureToolPayload(span, "tool.input", input);
            logToolInput(input, userId, sessionId);
            try {
                String delegateResult = delegate.call(input);
                tracing.setAttribute(span, "tool.output.length", textLength(delegateResult));
                tracing.captureToolPayload(span, "tool.output", delegateResult);
                tracing.setAttribute(span, "tool.status", "ok");
                logToolOutput(delegateResult, userId, sessionId);
                rememberToolSuccess(userId, sessionId, input, delegateResult);
                return delegateResult;
            }
            catch (RuntimeException ex) {
                writeToolErrorAttributes(span, ex);
                logToolError(ex, userId, sessionId);
                rememberToolError(userId, sessionId, input, ex);
                toolError[0] = ex;
                return null;
            }
        });
        if (toolError[0] != null) {
            throw toolError[0];
        }
        return result;
    }

    /**
     * 调用带 ToolContext 的工具，并记录本次工具执行的输入、输出和错误状态。
     */
    @Override
    public String call(String input, ToolContext toolContext) {
        RuntimeException[] toolError = new RuntimeException[1];
        String result = tracing.inSpan(toolSpanName(), () -> {
            Span span = tracing.currentSpan();
            String resolvedUserId = resolveContextValue(toolContext, userId, "userId", "mallUsername");
            String resolvedSessionId = resolveContextValue(toolContext, sessionId, "sessionId");
            writeToolStartAttributes(span, input, resolvedUserId, resolvedSessionId);
            tracing.captureToolPayload(span, "tool.input", input);
            logToolInput(input, resolvedUserId, resolvedSessionId);
            try {
                String delegateResult = delegate.call(input, toolContext);
                tracing.setAttribute(span, "tool.output.length", textLength(delegateResult));
                tracing.captureToolPayload(span, "tool.output", delegateResult);
                tracing.setAttribute(span, "tool.status", "ok");
                logToolOutput(delegateResult, resolvedUserId, resolvedSessionId);
                rememberToolSuccess(resolvedUserId, resolvedSessionId, input, delegateResult);
                return delegateResult;
            }
            catch (RuntimeException ex) {
                writeToolErrorAttributes(span, ex);
                logToolError(ex, resolvedUserId, resolvedSessionId);
                rememberToolError(resolvedUserId, resolvedSessionId, input, ex);
                toolError[0] = ex;
                return null;
            }
        });
        if (toolError[0] != null) {
            throw toolError[0];
        }
        return result;
    }

    private void rememberToolSuccess(String currentUserId,
                                     String currentSessionId,
                                     String input,
                                     String output) {
        if (toolCallMemoryService == null) {
            return;
        }
        try {
            toolCallMemoryService.rememberSuccess(currentUserId, currentSessionId, toolName(), input, output);
        } catch (RuntimeException ignored) {
            // 工具窗口是旁路上下文，不能改变原工具调用返回值或异常行为。
        }
    }

    private void rememberToolError(String currentUserId,
                                   String currentSessionId,
                                   String input,
                                   RuntimeException ex) {
        if (toolCallMemoryService == null) {
            return;
        }
        try {
            toolCallMemoryService.rememberError(currentUserId, currentSessionId, toolName(), input, ex);
        } catch (RuntimeException ignored) {
            // 工具窗口是旁路上下文，不能改变原工具调用返回值或异常行为。
        }
    }

    private void writeToolStartAttributes(Span span, String input, String currentUserId, String currentSessionId) {
        tracing.setAttribute(span, "tool.name", toolName());
        tracing.setAttribute(span, "tool.input.length", textLength(input));
        tracing.setAttribute(span, "app.user_id", safeContextText(currentUserId));
        tracing.setAttribute(span, "app.session_id", safeContextText(currentSessionId));
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

    private void logToolInput(String input, String currentUserId, String currentSessionId) {
        log.info("ReAct tool start: userId={}, sessionId={}, toolName={}, inputLength={}",
                safeContextText(currentUserId),
                safeContextText(currentSessionId),
                toolName(),
                textLength(input));
    }

    private void logToolOutput(String result, String currentUserId, String currentSessionId) {
        log.info("ReAct tool finish: userId={}, sessionId={}, toolName={}, outputLength={}",
                safeContextText(currentUserId),
                safeContextText(currentSessionId),
                toolName(),
                textLength(result));
    }

    private void logToolError(RuntimeException ex, String currentUserId, String currentSessionId) {
        log.warn("ReAct tool error: userId={}, sessionId={}, toolName={}, errorType={}",
                safeContextText(currentUserId),
                safeContextText(currentSessionId),
                toolName(),
                ex.getClass().getSimpleName());
    }

    private String resolveContextValue(ToolContext toolContext, String fallback, String... keys) {
        if (toolContext != null && toolContext.getContext() != null && keys != null) {
            for (String key : keys) {
                Object value = toolContext.getContext().get(key);
                if (value != null && StringUtils.hasText(value.toString())) {
                    return value.toString().trim();
                }
            }
        }
        return fallback;
    }

    private String safeContextText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String toolName() {
        ToolDefinition definition = getToolDefinition();
        return definition == null ? "<unknown>" : definition.name();
    }

    private int textLength(String text) {
        return text == null ? 0 : text.length();
    }
}
