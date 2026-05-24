package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallMcpToolCallbackTest {

    @Test
    void shouldResolveOnlyMallToolsFromSpringMcpProvider() {
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(syncClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                tool("mall_get_product_detail"),
                tool("web_search")
        ), null));
        MallMcpToolCallback mallToolCallback = new MallMcpToolCallback(mallMcpClient);

        List<ToolCallback> callbacks = mallToolCallback.getToolCallbacks();

        assertEquals(1, callbacks.size());
        assertEquals("mall_get_product_detail", callbacks.get(0).getToolDefinition().name());
        verify(mallMcpClient).ensureInitialized();
    }

    @Test
    void shouldInjectSessionIdWhenCallingResolvedMallTool() {
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(syncClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                tool("mall_get_product_detail")
        ), null));
        when(syncClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("{\"ok\":true}")),
                false
        ));
        MallMcpToolCallback mallToolCallback = new MallMcpToolCallback(mallMcpClient);

        mallToolCallback.getToolCallbacks().get(0).call(
                "{\"skuId\":3020}",
                new ToolContext(Map.of("sessionId", "session-1"))
        );

        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(syncClient).callTool(captor.capture());
        assertEquals("mall_get_product_detail", captor.getValue().name());
        assertEquals(3020, captor.getValue().arguments().get("skuId"));
        assertEquals("session-1", captor.getValue().arguments().get("sessionId"));
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(name + " description from mcp")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of("skuId", Map.of("type", "integer")),
                        List.of(),
                        null,
                        null,
                        null
                ))
                .build();
    }
}
