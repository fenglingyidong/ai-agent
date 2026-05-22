package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpContextClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

final class MallMcpToolCallback {

    private static final Logger log = LoggerFactory.getLogger(MallMcpToolCallback.class);
    private static final String TOOL_CONTEXT_USER_ID = "userId";
    private static final String TOOL_CONTEXT_SESSION_ID = "sessionId";
    private static final String TOOL_CONTEXT_MALL_TOKEN = "mallToken";
    private static final String TOOL_CONTEXT_MALL_USERNAME = "mallUsername";
    private static final String TOOL_CONTEXT_MALL_PASSWORD = "mallPassword";

    private MallMcpToolCallback() {
    }

    static List<ToolCallback> createToolCallbacks(ObjectMapper objectMapper,
                                                  MallMcpOperations mallMcpOperations,
                                                  MallMcpContextClient contextClient) {
        return MallTool.all().stream()
                .map(tool -> createCallback(tool, objectMapper, mallMcpOperations, contextClient))
                .toList();
    }

    static boolean isMallTool(String toolName) {
        return MallTool.isMallTool(toolName);
    }

    private static ToolCallback createCallback(MallTool tool,
                                               ObjectMapper objectMapper,
                                               MallMcpOperations mallMcpOperations,
                                               MallMcpContextClient contextClient) {
        return FunctionToolCallback
                .<Map<String, Object>, String>builder(tool.toolName(), (arguments, toolContext) ->
                        callMallMcp(tool, arguments, toolContext, objectMapper, mallMcpOperations, contextClient))
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .inputType(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .build();
    }

    private static String callMallMcp(MallTool tool,
                                      Map<String, Object> arguments,
                                      ToolContext toolContext,
                                      ObjectMapper objectMapper,
                                      MallMcpOperations mallMcpOperations,
                                      MallMcpContextClient contextClient) {
        MallMcpContextClient.MallMcpContextRegistration registration = contextClient.register(
                toolContextValue(toolContext, TOOL_CONTEXT_USER_ID),
                toolContextValue(toolContext, TOOL_CONTEXT_SESSION_ID),
                toolContextValue(toolContext, TOOL_CONTEXT_MALL_TOKEN),
                toolContextValue(toolContext, TOOL_CONTEXT_MALL_USERNAME),
                toolContextValue(toolContext, TOOL_CONTEXT_MALL_PASSWORD)
        );
        if (!registration.ok()) {
            return failure(objectMapper, "商城 MCP 调用失败：" + registration.message());
        }

        ObjectNode mcpArguments = toObjectNode(objectMapper, arguments);
        mcpArguments.put("sessionId", defaultSessionId(toolContextValue(toolContext, TOOL_CONTEXT_SESSION_ID)));
        MallMcpOperations.MallMcpCallResult result = mallMcpOperations.callTool(tool.toolName(), mcpArguments);
        if (!result.ok()) {
            log.warn("mall-mcp 工具调用失败：toolName={}, sessionId={}, error={}",
                    tool.toolName(), mcpArguments.path("sessionId").asText(), result.message());
        }
        return result.rawResult();
    }

    private static ObjectNode toObjectNode(ObjectMapper objectMapper, Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.valueToTree(arguments);
    }

    private static String defaultSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : "default";
    }

    private static String toolContextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null || !StringUtils.hasText(key)) {
            return "";
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String failure(ObjectMapper objectMapper, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ok", false);
        node.put("code", "MALL_ERROR");
        node.put("message", StringUtils.hasText(message) ? message : "商城 MCP 调用失败");
        node.putNull("data");
        return node.toString();
    }
}
