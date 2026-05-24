package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

final class OrderCreationGuardedToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BLOCKED_RESULT = """
            {"ok":false,"code":"ORDER_CREATION_BLOCKED","message":"Order creation requires an allowed route, confirmationId and userConfirmed=true."}
            """.trim();

    private final ToolCallback delegate;
    private final boolean orderCreationAllowed;

    OrderCreationGuardedToolCallback(ToolCallback delegate, boolean orderCreationAllowed) {
        this.delegate = delegate;
        this.orderCreationAllowed = orderCreationAllowed;
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
        if (!isAllowed(input)) {
            return BLOCKED_RESULT;
        }
        return delegate.call(input, toolContext);
    }

    private boolean isAllowed(String input) {
        if (!orderCreationAllowed) {
            return false;
        }
        JsonNode root = parseInput(input);
        String confirmationId = root.path("confirmationId").asText("");
        boolean userConfirmed = root.path("userConfirmed").asBoolean(false);
        return StringUtils.hasText(confirmationId) && userConfirmed;
    }

    private JsonNode parseInput(String input) {
        try {
            if (!StringUtils.hasText(input)) {
                return OBJECT_MAPPER.createObjectNode();
            }
            return OBJECT_MAPPER.readTree(input);
        }
        catch (Exception ex) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }
}
