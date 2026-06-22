package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 为 mall_create_order 增加 Java 侧二次确认门禁。
 */
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

    /**
     * 调用无上下文下单工具，仍会执行确认参数校验。
     */
    @Override
    public String call(String input) {
        return call(input, null);
    }

    /**
     * 校验路由授权、confirmationId 和 userConfirmed 后再放行真实下单工具。
     */
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
        if (!StringUtils.hasText(confirmationId)) {
            return "MISSING_CONFIRMATION_ID";
        }
        JsonNode userConfirmedNode = root.get("userConfirmed");
        if (userConfirmedNode == null || !userConfirmedNode.isBoolean() || !userConfirmedNode.booleanValue()) {
            return "USER_NOT_CONFIRMED";
        }
        return null;
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
