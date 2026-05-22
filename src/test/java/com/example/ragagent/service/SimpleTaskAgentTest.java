package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpClient;
import com.example.ragagent.tools.BuiltInTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleTaskAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        List<ToolCallback> callbacks = capturedToolCallbacks(mocks);
        assertEquals(1, callbacks.size());
        assertEquals("searchProductKnowledge", callbacks.get(0).getToolDefinition().name());
    }

    @Test
    void shouldRunMallTaskWithOnlySimpleMallTools() {
        AgentMocks mocks = agentMocks("儿童积木套装 300片售价 149.00 元，库存充足。");
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.chatClient, null, mock(MallMcpOperations.class));
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
        assertEquals(Set.of("queryRealtimeProduct", "addToCart", "viewCart", "prepareOrder"), Set.copyOf(names));
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
    void shouldResolveUniqueProductAndCallAddToCart() {
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        MallMcpOperations operations = new MallMcpOperations(objectMapper, mallMcpClient);
        SimpleTaskAgent.MallFastLaneTools tools = new SimpleTaskAgent.MallFastLaneTools(
                operations,
                "session-1",
                "把儿童积木套装 300片加购物车，数量1"
        );
        when(mallMcpClient.callTool(eq("mall_search_products"), any(ObjectNode.class))).thenReturn("""
                {"ok":true,"code":"OK","message":"success","data":[{"skuId":3020,"skuName":"儿童积木套装 300片","price":149.00,"stock":160}]}
                """);
        when(mallMcpClient.callTool(eq("mall_add_to_cart"), any(ObjectNode.class))).thenReturn("""
                {"ok":true,"code":"OK","message":"success","data":{"skuId":3020,"quantity":1}}
                """);

        String result = tools.addToCart("儿童积木套装 300片", null, 1);

        assertTrue(result.contains("\"ok\":true"));
        ArgumentCaptor<ObjectNode> addArgs = ArgumentCaptor.forClass(ObjectNode.class);
        verify(mallMcpClient).callTool(eq("mall_add_to_cart"), addArgs.capture());
        assertEquals(3020, addArgs.getValue().path("skuId").asInt());
        assertEquals(1, addArgs.getValue().path("quantity").asInt());
        assertEquals("session-1", addArgs.getValue().path("sessionId").asText());
    }

    @Test
    void shouldReturnMcpFailureDirectlyWhenSimpleModelCallSeesMcpUnavailable() {
        AgentMocks mocks = agentMocks("不会返回");
        when(mocks.requestSpec.call()).thenThrow(new SimpleTaskAgent.McpUnavailableException("mall-mcp 服务未启动或不可访问"));
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.chatClient, null, mock(MallMcpOperations.class));
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

    private AgentMocks agentMocks(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);
        return new AgentMocks(chatClient, requestSpec);
    }

    private List<ToolCallback> capturedToolCallbacks(AgentMocks mocks) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(mocks.requestSpec).tools(captor.capture());
        return List.of(ToolCallbacks.from(captor.getValue()));
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    private record AgentMocks(ChatClient chatClient, ChatClient.ChatClientRequestSpec requestSpec) {
    }
}
