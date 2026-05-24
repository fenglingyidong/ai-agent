package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallSessionToolCallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldInjectSessionIdFromContextWhenInputObjectDoesNotHaveSessionId() throws Exception {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of("sessionId", "session-1"));

        String result = callback.call("{\"skuId\":3020}", toolContext);

        assertEquals("ok", result);
        JsonNode delegatedInput = delegatedInput(delegate);
        assertEquals(3020, delegatedInput.path("skuId").asInt());
        assertEquals("session-1", delegatedInput.path("sessionId").asText());
    }

    @Test
    void shouldKeepExistingSessionId() {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of("sessionId", "session-1"));

        callback.call("{\"skuId\":3020,\"sessionId\":\"existing-session\"}", toolContext);

        verify(delegate).call(eq("{\"skuId\":3020,\"sessionId\":\"existing-session\"}"), eq(toolContext));
    }

    @Test
    void shouldInjectSessionIdWhenExistingSessionIdIsEmpty() throws Exception {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of("sessionId", "session-1"));

        callback.call("{\"skuId\":3020,\"sessionId\":\"\"}", toolContext);

        JsonNode delegatedInput = delegatedInput(delegate);
        assertEquals("session-1", delegatedInput.path("sessionId").asText());
    }

    @Test
    void shouldInjectSessionIdWhenInputIsNull() throws Exception {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of("sessionId", " session-1 "));

        callback.call(null, toolContext);

        JsonNode delegatedInput = delegatedInput(delegate);
        assertEquals("session-1", delegatedInput.path("sessionId").asText());
    }

    @Test
    void shouldInjectSessionIdWhenInputIsEmpty() throws Exception {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of("sessionId", " session-1 "));

        callback.call("", toolContext);

        JsonNode delegatedInput = delegatedInput(delegate);
        assertEquals("session-1", delegatedInput.path("sessionId").asText());
    }

    @Test
    void shouldInjectSessionIdWhenInputIsBlank() throws Exception {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of("sessionId", " session-1 "));

        callback.call("   ", toolContext);

        JsonNode delegatedInput = delegatedInput(delegate);
        assertEquals("session-1", delegatedInput.path("sessionId").asText());
    }

    @Test
    void shouldDelegateOriginalInputWhenContextHasNoSessionId() {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of());

        callback.call("{\"skuId\":3020}", toolContext);

        verify(delegate).call(eq("{\"skuId\":3020}"), eq(toolContext));
    }

    @Test
    void shouldDelegateOriginalInputWhenInputIsNotJsonObject() {
        ToolCallback delegate = delegateReturning("ok");
        MallSessionToolCallback callback = new MallSessionToolCallback(delegate);
        ToolContext toolContext = new ToolContext(Map.of("sessionId", "session-1"));

        callback.call("not-json", toolContext);
        callback.call("[{\"skuId\":3020}]", toolContext);

        verify(delegate).call(eq("not-json"), eq(toolContext));
        verify(delegate).call(eq("[{\"skuId\":3020}]"), eq(toolContext));
    }

    private ToolCallback delegateReturning(String output) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.call(any(String.class), any(ToolContext.class))).thenReturn(output);
        return delegate;
    }

    private JsonNode delegatedInput(ToolCallback delegate) throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(delegate).call(captor.capture(), any(ToolContext.class));
        return objectMapper.readTree(captor.getValue());
    }
}
