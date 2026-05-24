package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import java.util.Objects;

final class MallSessionToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolCallback delegate;

    MallSessionToolCallback(ToolCallback delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        return call(input, null);
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        return delegate.call(inputWithSession(input, sessionId(toolContext)), toolContext);
    }

    private String inputWithSession(String input, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return input;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(input);
            if (!root.isObject()) {
                return input;
            }
            ObjectNode object = (ObjectNode) root;
            if (!StringUtils.hasText(object.path("sessionId").asText(""))) {
                object.put("sessionId", sessionId);
            }
            return OBJECT_MAPPER.writeValueAsString(object);
        }
        catch (Exception ex) {
            return input;
        }
    }

    private String sessionId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get("sessionId");
        return value == null ? null : value.toString();
    }
}
