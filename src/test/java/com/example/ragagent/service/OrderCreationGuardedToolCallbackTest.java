package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCreationGuardedToolCallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBlockWhenRouteDoesNotAllowOrderCreation() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, false);

        String result = callback.call("{\"confirmationId\":\"confirm-1\",\"userConfirmed\":true}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        assertTrue(result.contains("\"reason\":\"ROUTE_NOT_ALLOWED\""));
        verify(delegate, never()).call(any(String.class));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldBlockWhenConfirmationIdIsMissing() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call("{\"userConfirmed\":true}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        assertTrue(result.contains("\"reason\":\"MISSING_CONFIRMATION_ID\""));
        verify(delegate, never()).call(any(String.class));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldBlockWhenUserConfirmedIsFalse() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call("{\"confirmationId\":\"confirm-1\",\"userConfirmed\":false}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        assertTrue(result.contains("\"reason\":\"USER_NOT_CONFIRMED\""));
        verify(delegate, never()).call(any(String.class));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldBlockWhenUserConfirmedIsStringTrue() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call("{\"confirmationId\":\"confirm-1\",\"userConfirmed\":\"true\"}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        assertTrue(result.contains("\"reason\":\"USER_NOT_CONFIRMED\""));
        verify(delegate, never()).call(any(String.class));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldBlockWhenUserConfirmedIsNumberOne() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call("{\"confirmationId\":\"confirm-1\",\"userConfirmed\":1}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        assertTrue(result.contains("\"reason\":\"USER_NOT_CONFIRMED\""));
        verify(delegate, never()).call(any(String.class));
        verify(delegate, never()).call(any(String.class), any(ToolContext.class));
    }

    @Test
    void shouldBlockInvalidJson() {
        ToolCallback delegate = delegate();
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(delegate, true);

        String result = callback.call("{");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("ORDER_CREATION_BLOCKED"));
        assertTrue(result.contains("\"reason\":\"INVALID_ARGUMENTS\""));
        verify(delegate, never()).call(any(String.class));
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

    @Test
    void shouldOverrideModelProvidedSessionIdWithTrustedContextForConfirmedOrder() throws Exception {
        ToolCallback delegate = delegate();
        when(delegate.call(any(String.class), any(ToolContext.class))).thenReturn("{\"ok\":true}");
        ToolContext toolContext = new ToolContext(Map.of("sessionId", "session-1"));
        OrderCreationGuardedToolCallback callback = new OrderCreationGuardedToolCallback(
                new MallSessionToolCallback(delegate), true);

        String result = callback.call(
                "{\"confirmationId\":\"confirm-1\",\"userConfirmed\":true,\"sessionId\":\"attacker-session\"}",
                toolContext);

        assertEquals("{\"ok\":true}", result);
        JsonNode delegatedInput = delegatedInput(delegate);
        assertEquals("confirm-1", delegatedInput.path("confirmationId").asText());
        assertTrue(delegatedInput.path("userConfirmed").asBoolean());
        assertEquals("session-1", delegatedInput.path("sessionId").asText());
    }

    @Test
    void shouldRejectNullDelegate() {
        assertThrows(NullPointerException.class, () -> new OrderCreationGuardedToolCallback(null, true));
    }

    private ToolCallback delegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_create_order");
        when(delegate.getToolDefinition()).thenReturn(definition);
        return delegate;
    }

    private JsonNode delegatedInput(ToolCallback delegate) throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(delegate).call(captor.capture(), any(ToolContext.class));
        return objectMapper.readTree(captor.getValue());
    }
}
