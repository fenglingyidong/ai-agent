package com.example.ragagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCreationGuardedToolCallbackTest {

    @Test
    void shouldBlockWhenRouteDoesNotAllowOrderCreation() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, false);

        String result = callback.call("{\"confirmationId\":\"confirm-1\",\"userConfirmed\":true}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldBlockWhenConfirmationIdIsMissing() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call("{\"userConfirmed\":true}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldBlockWhenUserConfirmedIsFalse() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call("{\"confirmationId\":\"confirm-1\",\"userConfirmed\":false}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldCallDelegateWhenRouteAllowsAndInputIsConfirmed() {
        ToolCallback delegate = delegate();
        ToolContext toolContext = mock(ToolContext.class);
        String input = "{\"confirmationId\":\"confirm-1\",\"userConfirmed\":true}";
        when(delegate.call(input, toolContext)).thenReturn("{\"ok\":true}");
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call(input, toolContext);

        assertEquals("{\"ok\":true}", result);
        verify(delegate).call(input, toolContext);
    }

    private ToolCallback delegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_create_order");
        when(delegate.getToolDefinition()).thenReturn(definition);
        return delegate;
    }
}
