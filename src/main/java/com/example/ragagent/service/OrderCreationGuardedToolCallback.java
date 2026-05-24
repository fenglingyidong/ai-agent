package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import java.util.Objects;

final class OrderCreationGuardedToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BLOCKED_RESULT_TEMPLATE = """
            {"ok":false,"code":"ORDER_CREATION_BLOCKED","reason":"%s","message":"Order creation requires an allowed route, confirmationId and userConfirmed=true."}
            """.trim();

    private final ToolCallback delegate;
    private final boolean orderCreationAllowed;

    OrderCreationGuardedToolCallback(ToolCallback delegate, boolean orderCreationAllowed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
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
        String blockReason = blockReason(input);
        if (blockReason != null) {
            return blockedResult(blockReason);
        }
        return delegate.call(input, toolContext);
    }

    private String blockReason(String input) {
        if (!orderCreationAllowed) {
            return "ROUTE_NOT_ALLOWED";
        }
        JsonNode root = parseInput(input);
        if (root == null || !root.isObject()) {
            return "INVALID_ARGUMENTS";
        }
        String confirmationId = root.path("confirmationId").asText("");
        boolean userConfirmed = root.path("userConfirmed").asBoolean(false);
        if (!StringUtils.hasText(confirmationId)) {
            return "MISSING_CONFIRMATION_ID";
        }
        return userConfirmed ? null : "USER_NOT_CONFIRMED";
    }

    private JsonNode parseInput(String input) {
        try {
            if (!StringUtils.hasText(input)) {
                return OBJECT_MAPPER.createObjectNode();
            }
            return OBJECT_MAPPER.readTree(input);
        }
        catch (Exception ex) {
            return null;
        }
    }

    private String blockedResult(String reason) {
        return BLOCKED_RESULT_TEMPLATE.formatted(reason);
    }
}
