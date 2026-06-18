package com.example.ragagent.service;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.mall.MallMcpClient;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.prompt.PromptTemplateStore;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.tools.BuiltInTools;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReActAgentTaskPolicyTest {

    @Test
    void runStreamShouldComposeTaskPolicyPromptAndFilterShoppingControlledTools() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        ShoppingRouteExecutor routeExecutor = mock(ShoppingRouteExecutor.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        List<String> systemPrompts = new ArrayList<>();
        List<ToolCallback> registeredCallbacks = new ArrayList<>();
        ShoppingTaskPolicy noMallPolicy = new ShoppingTaskPolicy(
                "NO_MALL_TEST",
                "无商城工具测试",
                Set.of("RECOMMENDATION"),
                Set.of(),
                Set.of("searchProductKnowledge"),
                false,
                "测试策略：只允许检索商品知识。"
        );

        when(conversationMemoryService.buildConversationId(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "::" + invocation.getArgument(1));
        when(routeExecutor.routeBeforeCore("user-1", "session-1", "帮我推荐", List.of(), "", "", ""))
                .thenReturn(new RoutedAgentRequest("帮我推荐", List.of(), null, false, List.of(noMallPolicy)));
        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenAnswer(invocation -> {
            systemPrompts.add(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenAnswer(invocation -> {
            registeredCallbacks.clear();
            registeredCallbacks.addAll(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("请补充预算"));

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                mock(LongTermMemoryAdvisor.class),
                memoryAdvisor(),
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                routeExecutor,
                List.of(),
                mock(ConversationLogService.class)
        );

        collect(agent.runStream("user-1", "session-1", null, "帮我推荐", false,
                List.of(), "", "", ""));

        assertTrue(systemPrompts.get(0).contains("当前导购任务策略"));
        assertTrue(systemPrompts.get(0).contains("测试策略：只允许检索商品知识。"));
        assertTrue(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("searchProductKnowledge"::equals));
        assertFalse(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("updateShoppingPreference"::equals));
    }

    @Test
    void runStreamShouldRenderDynamicPromptFragmentsFromTemplates() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        ShoppingRouteExecutor routeExecutor = mock(ShoppingRouteExecutor.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        List<String> systemPrompts = new ArrayList<>();
        ShoppingTaskPolicy policy = new ShoppingTaskPolicy(
                "TEST_POLICY",
                "测试策略",
                Set.of("COMPLEX_REACT"),
                Set.of(),
                Set.of("searchProductKnowledge"),
                true,
                "测试策略片段。"
        );

        when(conversationMemoryService.buildConversationId(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "::" + invocation.getArgument(1));
        when(routeExecutor.routeBeforeCore("user-1", "session-1", "帮我推荐静音鼠标", List.of(), "", "", ""))
                .thenReturn(new RoutedAgentRequest("帮我推荐静音鼠标", List.of(), null, false, List.of(policy)));
        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenAnswer(invocation -> {
            systemPrompts.add(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("推荐静音鼠标"));

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                mock(LongTermMemoryAdvisor.class),
                memoryAdvisor(),
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                routeExecutor,
                List.of(),
                null,
                mock(ConversationLogService.class),
                new RagTracing(),
                PromptTemplateStore.fromClasspath("prompts/react-fragments-test")
        );

        collect(agent.runStream("user-1", "session-1", null, "帮我推荐静音鼠标", false,
                List.of(), "", "", ""));

        String systemPrompt = systemPrompts.get(0);
        assertTrue(systemPrompt.contains("TEST_MALL_DISABLED_RULE"));
        assertTrue(systemPrompt.contains("TEST_NETWORK_DISABLED_RULE"));
        assertTrue(systemPrompt.contains("TEST_TASK_POLICY_HEADER"));
        assertTrue(systemPrompt.contains("TEST_POLICY / 测试策略：测试策略片段。"));
        assertTrue(systemPrompt.contains("TEST_ALLOWED_TOOLS searchProductKnowledge。"));
        assertTrue(systemPrompt.contains("TEST_CONFIRMATION_REQUIRED"));
    }

    @Test
    void runStreamShouldNotDiscoverMallToolsWhenTaskPolicyDisallowsMallTools() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        ShoppingRouteExecutor routeExecutor = mock(ShoppingRouteExecutor.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        List<ToolCallback> registeredCallbacks = new ArrayList<>();
        ShoppingTaskPolicy noMallPolicy = new ShoppingTaskPolicy(
                "NO_MALL_TEST",
                "无商城工具测试",
                Set.of("PRODUCT_SELECTION"),
                Set.of(),
                Set.of("searchProductKnowledge"),
                false,
                "测试策略：只允许检索商品知识。"
        );

        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(conversationMemoryService.buildConversationId(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "::" + invocation.getArgument(1));
        when(routeExecutor.routeBeforeCore("user-1", "session-1", "查库存", List.of(), "", "", ""))
                .thenReturn(new RoutedAgentRequest("查库存", List.of(), null, true, List.of(noMallPolicy)));
        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenAnswer(invocation -> {
            registeredCallbacks.clear();
            registeredCallbacks.addAll(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("请补充具体 SKU"));

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                mock(LongTermMemoryAdvisor.class),
                memoryAdvisor(),
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                routeExecutor,
                List.of(),
                mallMcpClient,
                mock(ConversationLogService.class)
        );

        collect(agent.runStream("user-1", "session-1", null, "查库存", false,
                List.of(), "", "", ""));

        verify(mallMcpClient, never()).ensureInitialized();
        verify(syncClient, never()).listTools();
        assertTrue(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("searchProductKnowledge"::equals));
        assertFalse(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("updateShoppingPreference"::equals));
        assertFalse(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch(MallMcpToolCallback::isMallTool));
    }

    @Test
    void runStreamShouldFilterCreateOrderWhenRouteDoesNotAllowOrderCreation() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        ShoppingRouteExecutor routeExecutor = mock(ShoppingRouteExecutor.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        List<ToolCallback> registeredCallbacks = new ArrayList<>();
        ShoppingTaskPolicy orderPolicy = orderPolicy();

        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(syncClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("mall_create_order")), null));
        when(conversationMemoryService.buildConversationId(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "::" + invocation.getArgument(1));
        when(routeExecutor.routeBeforeCore("user-1", "session-1", "确认下单", List.of(), "", "", ""))
                .thenReturn(new RoutedAgentRequest("确认下单", List.of(), null, true, List.of(orderPolicy), false));
        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenAnswer(invocation -> {
            registeredCallbacks.clear();
            registeredCallbacks.addAll(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("需要二次确认"));

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                mock(LongTermMemoryAdvisor.class),
                memoryAdvisor(),
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                routeExecutor,
                List.of(),
                mallMcpClient,
                mock(ConversationLogService.class)
        );

        collect(agent.runStream("user-1", "session-1", null, "确认下单", false,
                List.of(), "", "", ""));

        assertFalse(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("mall_create_order"::equals));
    }

    @Test
    void runStreamShouldRegisterCreateOrderWhenRouteAllowsOrderCreation() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        ShoppingRouteExecutor routeExecutor = mock(ShoppingRouteExecutor.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        List<ToolCallback> registeredCallbacks = new ArrayList<>();
        ShoppingTaskPolicy orderPolicy = orderPolicy();

        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(syncClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("mall_create_order")), null));
        when(conversationMemoryService.buildConversationId(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "::" + invocation.getArgument(1));
        when(routeExecutor.routeBeforeCore("user-1", "session-1", "确认下单", List.of(), "", "", ""))
                .thenReturn(new RoutedAgentRequest("确认下单", List.of(), null, true, List.of(orderPolicy), true));
        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenAnswer(invocation -> {
            registeredCallbacks.clear();
            registeredCallbacks.addAll(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("已创建"));

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                mock(LongTermMemoryAdvisor.class),
                memoryAdvisor(),
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                routeExecutor,
                List.of(),
                mallMcpClient,
                mock(ConversationLogService.class)
        );

        collect(agent.runStream("user-1", "session-1", null, "确认下单", false,
                List.of(), "", "", ""));

        ToolCallback createOrderCallback = registeredCallbacks.stream()
                .filter(callback -> "mall_create_order".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElse(null);
        assertNotNull(createOrderCallback);

        String blocked = createOrderCallback.call("{\"userConfirmed\":true}");
        assertTrue(blocked.contains("ORDER_CREATION_BLOCKED"));
        assertTrue(blocked.contains("MISSING_CONFIRMATION_ID"));
        verify(syncClient, never()).callTool(any(McpSchema.CallToolRequest.class));

        when(syncClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("{\"ok\":true,\"orderId\":\"order-1\"}")),
                false
        ));

        String result = createOrderCallback.call("{\"confirmationId\":\"confirm-1\",\"userConfirmed\":true}");

        assertTrue(result.contains("商城返回下单结果"));
        assertTrue(result.contains("order-1"));
        var requestCaptor = forClass(McpSchema.CallToolRequest.class);
        verify(syncClient).callTool(requestCaptor.capture());
        assertEquals("mall_create_order", requestCaptor.getValue().name());
        assertEquals("confirm-1", requestCaptor.getValue().arguments().get("confirmationId"));
        assertEquals(Boolean.TRUE, requestCaptor.getValue().arguments().get("userConfirmed"));
    }

    private ChatClient.Builder builderFor(ChatClient reactChatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(reactChatClient);
        return builder;
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    private MessageChatMemoryAdvisor memoryAdvisor() {
        return MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build()
        ).build();
    }

    private ShoppingTaskPolicy orderPolicy() {
        return new ShoppingTaskPolicy(
                "CART_CONFIRMATION",
                "订单确认",
                Set.of("CART_CONFIRMATION"),
                Set.of(),
                Set.of("mall_create_order"),
                true,
                "订单创建必须二次确认。"
        );
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(name + " description from mcp")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of(
                                "confirmationId", Map.of("type", "string"),
                                "userConfirmed", Map.of("type", "boolean")
                        ),
                        List.of("confirmationId", "userConfirmed"),
                        null,
                        null,
                        null
                ))
                .build();
    }

}
