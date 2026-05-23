package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
