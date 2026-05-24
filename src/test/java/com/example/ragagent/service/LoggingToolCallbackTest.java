package com.example.ragagent.service;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingToolCallbackTest {

    @Test
    void shouldLogToolCallSummaryWithoutFullInputOrOutput() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingToolCallback.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ToolCallback delegate = delegateReturning("{\"ok\":true,\"token\":\"secret-token\"}");
            LoggingToolCallback callback = new LoggingToolCallback(delegate, "user-1", "session-1");

            callback.call("{\"skuId\":3020,\"token\":\"secret-token\"}", new ToolContext(Map.of()));

            String logs = appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logs.contains("toolName=mall_get_product_detail"));
            assertTrue(logs.contains("inputLength="));
            assertTrue(logs.contains("outputLength="));
            assertFalse(logs.contains("secret-token"));
            assertFalse(logs.contains("\"skuId\":3020"));
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private ToolCallback delegateReturning(String output) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_get_product_detail");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(eq("{\"skuId\":3020,\"token\":\"secret-token\"}"), any(ToolContext.class))).thenReturn(output);
        return delegate;
    }
}
