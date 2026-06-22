package com.example.ragagent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.memory.ConversationToolCallMemoryService;
import com.example.ragagent.memory.ConversationToolCallRecord;
import com.example.ragagent.observability.RagTracing;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoggingToolCallbackTest {

    @Test
    void callShouldReturnDelegateResultWithoutChangingToolPayload() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name("searchProductKnowledge")
                .description("检索商品知识")
                .inputSchema("{}")
                .build();
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call("敏感输入")).thenReturn("敏感输出");

        LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1", new RagTracing());

        String result = callback.call("敏感输入");

        assertEquals("敏感输出", result);
        verify(delegate).call("敏感输入");
    }

    @Test
    void callShouldRecordSuccessfulToolCallInToolMemoryUsingConstructorIds() {
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        ToolCallback delegate = delegateReturning("{\"skuId\":3020}", "{\"name\":\"键盘\"}");
        LoggingToolCallback callback = new LoggingToolCallback(
                delegate, "ctor-user", "ctor-session", new RagTracing(), toolMemory);

        String result = callback.call("{\"skuId\":3020}");

        assertEquals("{\"name\":\"键盘\"}", result);
        ConversationToolCallRecord record = toolMemory.records("ctor-user", "ctor-session").get(0);
        assertEquals("mall_get_product_detail", record.toolName());
        assertEquals("{\"skuId\":3020}", record.input());
        assertEquals("{\"name\":\"键盘\"}", record.output());
        assertEquals(ConversationToolCallRecord.Status.OK, record.status());
    }

    @Test
    void callWithToolContextShouldRecordSuccessfulToolCallUsingContextUserAndSession() {
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        ToolCallback delegate = delegateReturning("{\"skuId\":3020}", "{\"name\":\"键盘\"}");
        LoggingToolCallback callback = new LoggingToolCallback(
                delegate, "ctor-user", "ctor-session", new RagTracing(), toolMemory);

        String result = callback.call("{\"skuId\":3020}", new ToolContext(Map.of(
                "userId", "context-user",
                "sessionId", "context-session"
        )));

        assertEquals("{\"name\":\"键盘\"}", result);
        assertTrue(toolMemory.records("ctor-user", "ctor-session").isEmpty());
        ConversationToolCallRecord record = toolMemory.records("context-user", "context-session").get(0);
        assertEquals("mall_get_product_detail", record.toolName());
        assertEquals("{\"skuId\":3020}", record.input());
        assertEquals("{\"name\":\"键盘\"}", record.output());
        assertEquals(ConversationToolCallRecord.Status.OK, record.status());
    }

    @Test
    void callWithToolContextShouldFallbackToMallUsernameWhenUserIdIsMissing() {
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        ToolCallback delegate = delegateReturning("{\"skuId\":3020}", "{\"name\":\"键盘\"}");
        LoggingToolCallback callback = new LoggingToolCallback(
                delegate, "ctor-user", "ctor-session", new RagTracing(), toolMemory);

        callback.call("{\"skuId\":3020}", new ToolContext(Map.of(
                "mallUsername", "mall-user",
                "sessionId", "context-session"
        )));

        assertTrue(toolMemory.records("ctor-user", "context-session").isEmpty());
        ConversationToolCallRecord record = toolMemory.records("mall-user", "context-session").get(0);
        assertEquals("mall_get_product_detail", record.toolName());
        assertEquals("{\"skuId\":3020}", record.input());
        assertEquals("{\"name\":\"键盘\"}", record.output());
        assertEquals(ConversationToolCallRecord.Status.OK, record.status());
    }

    @Test
    void callShouldRethrowSameRuntimeExceptionAndRecordSafeErrorInToolMemory() {
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        RuntimeException exception = new RuntimeException("raw exception message should stay out of memory");
        ToolCallback delegate = delegateThrowing("{\"skuId\":3020}", exception);
        LoggingToolCallback callback = new LoggingToolCallback(
                delegate, "ctor-user", "ctor-session", new RagTracing(), toolMemory);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> callback.call("{\"skuId\":3020}"));

        assertSame(exception, thrown);
        ConversationToolCallRecord record = toolMemory.records("ctor-user", "ctor-session").get(0);
        assertEquals("mall_get_product_detail", record.toolName());
        assertEquals("{\"skuId\":3020}", record.input());
        assertEquals(ConversationToolCallRecord.Status.ERROR, record.status());
        assertEquals("RuntimeException", record.errorType());
        assertFalse(record.output().contains("raw exception message"));
        assertFalse(toolMemory.recentToolCallContext("ctor-user", "ctor-session")
                .contains("raw exception message"));
    }

    @Test
    void oldConstructorShouldKeepReturningDelegateResultWithoutToolMemory() {
        ToolCallback delegate = delegateReturning("{\"skuId\":3020}", "{\"name\":\"键盘\"}");
        LoggingToolCallback callback = new LoggingToolCallback(delegate, "ctor-user", "ctor-session", new RagTracing());

        String result = callback.call("{\"skuId\":3020}");

        assertEquals("{\"name\":\"键盘\"}", result);
        verify(delegate).call("{\"skuId\":3020}");
    }

    @Test
    void callShouldUseUnknownToolSpanNameWhenToolDefinitionIsMissing() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(null);
        when(delegate.call("敏感输入")).thenReturn("敏感输出");
        RecordingTracing tracing = new RecordingTracing();
        LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1", tracing);

        String result = callback.call("敏感输入");

        assertEquals("敏感输出", result);
        assertEquals("tool.unknown", tracing.spanName());
    }

    @Test
    void shouldLogToolCallSummaryWithoutFullInputOrOutput() {
        try (CapturedLogs capturedLogs = new CapturedLogs()) {
            ToolCallback delegate = delegateReturning("{\"ok\":true,\"token\":\"secret-token\"}");
            LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1");

            callback.call("{\"skuId\":3020,\"token\":\"secret-token\"}", new ToolContext(Map.of()));

            String logs = capturedLogs.formattedMessages();
            assertTrue(logs.contains("toolName=mall_get_product_detail"));
            assertTrue(logs.contains("inputLength="));
            assertTrue(logs.contains("outputLength="));
            assertFalse(logs.contains("secret-token"));
            assertFalse(logs.contains("\"skuId\":3020"));
        }
    }

    @Test
    void shouldLogSimpleCallSummaryWithoutFullInputOrOutput() {
        try (CapturedLogs capturedLogs = new CapturedLogs()) {
            ToolCallback delegate = delegateReturning("{\"ok\":true,\"token\":\"secret-token\"}");
            LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1");

            callback.call("{\"skuId\":3020,\"token\":\"secret-token\"}");

            String logs = capturedLogs.formattedMessages();
            assertTrue(logs.contains("toolName=mall_get_product_detail"));
            assertTrue(logs.contains("inputLength="));
            assertTrue(logs.contains("outputLength="));
            assertFalse(logs.contains("secret-token"));
            assertFalse(logs.contains("\"skuId\":3020"));
            assertFalse(logs.contains("{\"skuId\":3020,\"token\":\"secret-token\"}"));
        }
    }

    @Test
    void shouldLogToolErrorSummaryWithoutFullInputOrOutput() {
        try (CapturedLogs capturedLogs = new CapturedLogs()) {
            ToolCallback delegate = delegateThrowing(
                    new RuntimeException("failed input={\"skuId\":3020,\"token\":\"secret-token\"}"));
            LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1");

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> callback.call("{\"skuId\":3020,\"token\":\"secret-token\"}", new ToolContext(Map.of())));

            assertEquals("failed input={\"skuId\":3020,\"token\":\"secret-token\"}", thrown.getMessage());
            String logs = capturedLogs.formattedMessages();
            assertTrue(logs.contains("toolName=mall_get_product_detail"));
            assertTrue(logs.contains("errorType=RuntimeException"));
            assertFalse(logs.contains("secret-token"));
            assertFalse(logs.contains("\"skuId\":3020"));
            assertFalse(logs.contains("{\"skuId\":3020,\"token\":\"secret-token\"}"));
        }
    }

    @Test
    void callShouldNotRecordToolExceptionInSpanWhenDelegateThrows() {
        RuntimeException exception = new RuntimeException("failed input={\"skuId\":3020,\"token\":\"secret-token\"}");
        ToolCallback delegate = delegateThrowing(exception);
        RecordingTracing tracing = new RecordingTracing();
        LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1", tracing);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> callback.call("{\"skuId\":3020,\"token\":\"secret-token\"}", new ToolContext(Map.of())));

        assertEquals(exception, thrown);
        assertEquals(0, tracing.recordErrorCount());
        assertEquals("RuntimeException", tracing.stringAttribute("tool.error.type"));
        assertEquals("RuntimeException", tracing.stringAttribute("error.type"));
        assertEquals("error", tracing.stringAttribute("tool.status"));
    }

    @Test
    void callShouldCaptureSanitizedToolPayloadForDebugTracing() {
        ToolCallback delegate = delegateReturning("{\"ok\":true,\"token\":\"secret-token\"}");
        RecordingTracing tracing = new RecordingTracing();
        LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1", tracing);

        String result = callback.call("{\"skuId\":3020,\"token\":\"secret-token\"}", new ToolContext(Map.of()));

        assertEquals("{\"ok\":true,\"token\":\"secret-token\"}", result);
        assertEquals("{\"skuId\":3020,\"token\":\"[REDACTED]\"}", tracing.stringAttribute("tool.input"));
        assertEquals("{\"ok\":true,\"token\":\"[REDACTED]\"}", tracing.stringAttribute("tool.output"));
    }

    @Test
    void callShouldPreferToolContextUserAndSessionInTraceAttributes() {
        ToolCallback delegate = delegateReturning("{\"ok\":true,\"token\":\"secret-token\"}");
        RecordingTracing tracing = new RecordingTracing();
        LoggingToolCallback callback = new LoggingToolCallback(delegate, "simple-task", "simple-task", tracing);

        callback.call("{\"skuId\":3020,\"token\":\"secret-token\"}", new ToolContext(Map.of(
                "userId", "alice",
                "sessionId", "session-123"
        )));

        assertEquals("alice", tracing.stringAttribute("app.user_id"));
        assertEquals("session-123", tracing.stringAttribute("app.session_id"));
    }

    private ToolCallback delegateReturning(String output) {
        return delegateReturning("{\"skuId\":3020,\"token\":\"secret-token\"}", output);
    }

    private ToolCallback delegateReturning(String input, String output) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_get_product_detail");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(input)).thenReturn(output);
        when(delegate.call(eq(input), any(ToolContext.class))).thenReturn(output);
        return delegate;
    }

    private ToolCallback delegateThrowing(RuntimeException exception) {
        return delegateThrowing("{\"skuId\":3020,\"token\":\"secret-token\"}", exception);
    }

    private ToolCallback delegateThrowing(String input, RuntimeException exception) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_get_product_detail");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(input)).thenThrow(exception);
        when(delegate.call(eq(input), any(ToolContext.class))).thenThrow(exception);
        return delegate;
    }

    private static final class CapturedLogs implements AutoCloseable {

        private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingToolCallback.class);
        private final Level originalLevel = logger.getLevel();
        private final boolean originalAdditive = logger.isAdditive();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedLogs() {
            logger.setLevel(Level.INFO);
            logger.setAdditive(false);
            appender.start();
            logger.addAppender(appender);
        }

        private String formattedMessages() {
            return appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }
    }

    private static final class RecordingTracing extends RagTracing {

        private String spanName;
        private int recordErrorCount;
        private final Map<String, String> stringAttributes = new java.util.LinkedHashMap<>();

        @Override
        public <T> T inSpan(String spanName, Callable<T> callable) {
            this.spanName = spanName;
            try {
                return callable.call();
            }
            catch (RuntimeException ex) {
                recordError(null, ex);
                throw ex;
            }
            catch (Exception ex) {
                recordError(null, ex);
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void setAttribute(Span span, String key, String value) {
            stringAttributes.put(key, value);
        }

        @Override
        public void captureToolPayload(Span span, String key, String value) {
            stringAttributes.put(key, value == null ? "" : value.replace("secret-token", "[REDACTED]"));
        }

        @Override
        public void recordError(Span span, Throwable ex) {
            recordErrorCount++;
        }

        private String spanName() {
            return spanName;
        }

        private int recordErrorCount() {
            return recordErrorCount;
        }

        private String stringAttribute(String key) {
            return stringAttributes.get(key);
        }
    }
}
