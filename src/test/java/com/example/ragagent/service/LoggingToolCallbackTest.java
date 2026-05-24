package com.example.ragagent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingToolCallbackTest {

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

    private ToolCallback delegateReturning(String output) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_get_product_detail");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call("{\"skuId\":3020,\"token\":\"secret-token\"}")).thenReturn(output);
        when(delegate.call(eq("{\"skuId\":3020,\"token\":\"secret-token\"}"), any(ToolContext.class))).thenReturn(output);
        return delegate;
    }

    private ToolCallback delegateThrowing(RuntimeException exception) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_get_product_detail");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(eq("{\"skuId\":3020,\"token\":\"secret-token\"}"), any(ToolContext.class))).thenThrow(exception);
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
}
