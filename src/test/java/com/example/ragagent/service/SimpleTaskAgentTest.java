package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpClient;
import com.example.ragagent.tools.BuiltInTools;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleTaskAgentTest {

    @Test
    void shouldRunKnowledgeTaskWithOnlyRagTool() {
        AgentMocks mocks = agentMocks("儿童积木套装适合 3 岁以上儿童，主打益智启蒙。");
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.chatClient, mock(BuiltInTools.class), null);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_KNOWLEDGE_QUERY",
                "A_FAQ_SIMPLE_QUERY",
                Map.of(),
                Map.of("product_name", "儿童积木套装"),
                false,
                0.92,
                "知识库简单查询"
        );

        FastLaneResult result = agent.tryRun(route, "儿童积木套装有什么特点", "session-1", 0.7);

        assertTrue(result.handled());
        assertEquals("儿童积木套装适合 3 岁以上儿童，主打益智启蒙。", collect(result.stream()));
        List<ToolCallback> callbacks = capturedToolObjectCallbacks(mocks);
        assertEquals(1, callbacks.size());
        assertEquals("searchProductKnowledge", callbacks.get(0).getToolDefinition().name());
    }

    @Test
    void shouldRunMallTaskWithOnlySimpleMallTools() {
        AgentMocks mocks = agentMocks("儿童积木套装 300片售价 149.00 元，库存充足。");
        MallMcpClient mallMcpClient = mallMcpClientWithTools(
                "mall_search_products",
                "mall_get_product_detail",
                "mall_add_to_cart",
                "mall_view_cart",
                "mall_prepare_order",
                "mall_create_order"
        );
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.chatClient, null, mallMcpClient);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRICE_STOCK_QUERY",
                "B_SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木套装 300片"),
                false,
                0.95,
                "查价格"
        );

        FastLaneResult result = agent.tryRun(route, "儿童积木套装 300片要多少钱", "session-1", 0.7);

        assertTrue(result.handled());
        assertEquals("儿童积木套装 300片售价 149.00 元，库存充足。", collect(result.stream()));
        List<String> names = capturedToolCallbacks(mocks).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
        assertEquals(Set.of(
                "mall_search_products",
                "mall_get_product_detail",
                "mall_add_to_cart",
                "mall_view_cart",
                "mall_prepare_order"
        ), Set.copyOf(names));
        verify(mocks.requestSpec).toolContext(Map.of("sessionId", "session-1"));
    }

    @Test
    void shouldFallbackToCoreWhenKnowledgeToolReturnsEmpty() {
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        SimpleTaskAgent.KnowledgeFastLaneTools tools = new SimpleTaskAgent.KnowledgeFastLaneTools(builtInTools);
        when(builtInTools.searchProductKnowledge("未知商品"))
                .thenReturn("商品知识库中没有检索到相关内容。");

        assertThrows(SimpleTaskAgent.FastLaneFallbackException.class,
                () -> tools.searchProductKnowledge("未知商品"));
    }

    @Test
    void shouldReturnMcpFailureDirectlyWhenSimpleModelCallSeesMcpUnavailable() {
        AgentMocks mocks = agentMocks("不会返回");
        when(mocks.requestSpec.call()).thenThrow(new SimpleTaskAgent.McpUnavailableException("mall-mcp 服务未启动或不可访问"));
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.chatClient, null, mallMcpClientWithTools("mall_view_cart"));
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "VIEW_CART",
                "B_SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                false,
                0.9,
                "查购物车"
        );

        FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

        assertTrue(result.handled());
        assertEquals("商城 MCP 调用失败：mall-mcp 服务未启动或不可访问", collect(result.stream()));
    }

    @Test
    void shouldReturnMcpFailureDirectlyWhenMallToolExecutionFails() {
        AgentMocks mocks = agentMocks("不会返回");
        ToolExecutionException exception = new ToolExecutionException(
                toolDefinition("mall_view_cart"),
                new RuntimeException("mcp call failed")
        );
        when(mocks.requestSpec.call()).thenThrow(exception);
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.chatClient, null, mallMcpClientWithTools("mall_view_cart"));
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "VIEW_CART",
                "B_SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                false,
                0.9,
                "查购物车"
        );

        FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

        assertTrue(result.handled());
        assertTrue(collect(result.stream()).startsWith("商城 MCP 调用失败："));
    }

    private AgentMocks agentMocks(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);
        return new AgentMocks(chatClient, requestSpec);
    }

    private List<ToolCallback> capturedToolObjectCallbacks(AgentMocks mocks) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(mocks.requestSpec).tools(captor.capture());
        return List.of(ToolCallbacks.from(captor.getValue()));
    }

    private List<ToolCallback> capturedToolCallbacks(AgentMocks mocks) {
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(mocks.requestSpec).toolCallbacks(captor.capture());
        return captor.getValue();
    }

    private MallMcpClient mallMcpClientWithTools(String... toolNames) {
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(syncClient.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(toolNames).stream().map(this::tool).toList(),
                null
        ));
        return mallMcpClient;
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

    private ToolDefinition toolDefinition(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        return definition;
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    private record AgentMocks(ChatClient chatClient, ChatClient.ChatClientRequestSpec requestSpec) {
    }
}
