package com.example.ragagent.mall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class MallMcpClient {

    private final ObjectMapper objectMapper;
    private final McpSyncClient mcpClient;

    public MallMcpClient(ObjectMapper objectMapper, MallMcpProperties properties) {
        this.objectMapper = objectMapper;
        WebClient.Builder webClientBuilder = WebClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()));
        WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport.builder(webClientBuilder)
                .endpoint(normalizeEndpoint(properties.getEndpoint()))
                .build();
        this.mcpClient = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("rag-agent-mall-mcp-client", "mall", "1.0.0"))
                .requestTimeout(properties.getRequestTimeout())
                .build();
    }

    public String callTool(String toolName, ObjectNode arguments) {
        try {
            initializeIfNeeded();
            McpSchema.CallToolResult result = mcpClient.callTool(
                    new McpSchema.CallToolRequest(toolName, toArgumentMap(arguments))
            );
            return extractToolResult(result);
        }
        catch (RuntimeException ex) {
            if (isConnectionFailure(ex)) {
                throw new IllegalStateException("mall-mcp 服务未启动或不可访问", ex);
            }
            throw ex;
        }
    }

    public McpSyncClient syncClient() {
        return mcpClient;
    }

    public void ensureInitialized() {
        initializeIfNeeded();
    }

    @PreDestroy
    public void close() {
        try {
            mcpClient.closeGracefully();
        }
        catch (RuntimeException ignored) {
            mcpClient.close();
        }
    }

    private synchronized void initializeIfNeeded() {
        if (!mcpClient.isInitialized()) {
            mcpClient.initialize();
        }
    }

    private Map<String, Object> toArgumentMap(ObjectNode arguments) {
        ObjectNode safeArguments = arguments == null ? objectMapper.createObjectNode() : arguments;
        return objectMapper.convertValue(safeArguments, new TypeReference<>() {
        });
    }

    private String extractToolResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return "{}";
        }
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent && StringUtils.hasText(textContent.text())) {
                    return textContent.text();
                }
            }
        }
        if (result.structuredContent() != null) {
            return toJson(result.structuredContent());
        }
        return toJson(result);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize MCP tool result", ex);
        }
    }

    private boolean isConnectionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("WebClientRequestException")
                    || containsIgnoreCase(message, "connection refused")
                    || containsIgnoreCase(message, "connection timed out")
                    || containsIgnoreCase(message, "connect timed out")
                    || containsIgnoreCase(message, "failed to connect")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String fragment) {
        return StringUtils.hasText(value)
                && StringUtils.hasText(fragment)
                && value.toLowerCase(java.util.Locale.ROOT).contains(fragment.toLowerCase(java.util.Locale.ROOT));
    }

    private String normalizeEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return "/mcp";
        }
        endpoint = endpoint.trim();
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        return endpoint;
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "http://localhost:8120";
        }
        return value.trim().replaceAll("/+$", "");
    }
}
