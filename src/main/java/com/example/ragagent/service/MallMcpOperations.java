package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MallMcpOperations {

    private final ObjectMapper objectMapper;
    private final MallMcpClient mallMcpClient;

    public MallMcpOperations(ObjectMapper objectMapper, MallMcpClient mallMcpClient) {
        this.objectMapper = objectMapper;
        this.mallMcpClient = mallMcpClient;
    }

    MallMcpCallResult callTool(String toolName, ObjectNode arguments) {
        ObjectNode safeArguments = arguments == null ? objectMapper.createObjectNode() : arguments;
        try {
            String raw = mallMcpClient.callTool(toolName, safeArguments);
            JsonNode envelope = parseEnvelope(raw);
            return new MallMcpCallResult(toolName, safeArguments, raw, envelope, false);
        }
        catch (RuntimeException ex) {
            String message = safeMessage(ex);
            return new MallMcpCallResult(
                    toolName,
                    safeArguments,
                    failureJson(message),
                    failureEnvelope(message),
                    message.contains("mall-mcp 服务未启动或不可访问")
            );
        }
    }

    ObjectNode argsWithSession(String sessionId) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("sessionId", StringUtils.hasText(sessionId) ? sessionId.trim() : "");
        return args;
    }

    private JsonNode parseEnvelope(String raw) {
        if (!StringUtils.hasText(raw)) {
            return failureEnvelope("empty MCP response");
        }
        try {
            return objectMapper.readTree(raw);
        }
        catch (Exception ex) {
            ObjectNode node = failureEnvelope("invalid MCP response");
            node.put("raw", raw);
            return node;
        }
    }

    private ObjectNode failureEnvelope(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ok", false);
        node.put("code", "MALL_ERROR");
        node.put("message", StringUtils.hasText(message) ? message : "商城 MCP 调用失败");
        node.putNull("data");
        return node;
    }

    private String failureJson(String message) {
        return failureEnvelope(message).toString();
    }

    private String safeMessage(RuntimeException ex) {
        return ex == null || !StringUtils.hasText(ex.getMessage()) ? "unknown error" : ex.getMessage();
    }

    record MallMcpCallResult(
            String toolName,
            ObjectNode arguments,
            String rawResult,
            JsonNode envelope,
            boolean serviceUnavailable
    ) {

        boolean ok() {
            return envelope != null && envelope.path("ok").asBoolean(false);
        }

        String message() {
            return envelope == null ? "unknown error" : envelope.path("message").asText("unknown error");
        }
    }
}
