package com.example.ragagent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.memory.ConversationToolCallMemoryService;
import com.example.ragagent.memory.ConversationToolCallRecord;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.tools.BuiltInTools;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        when(mocks.requestSpec.system(systemPromptCaptor.capture())).thenReturn(mocks.requestSpec);
        SimpleTaskAgent agent = agent(mocks, mock(BuiltInTools.class), null);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "FAQ_SIMPLE_QUERY",
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
        String systemPrompt = systemPromptCaptor.getValue();
        assertTrue(systemPrompt.contains("知识库原文事实"));
        assertTrue(systemPrompt.contains("价格桶只用于预算召回和初筛"));
        assertTrue(systemPrompt.contains("工具无结果或失败时"));
        assertTrue(!systemPrompt.contains("推荐、选哪个、更合适、别太复杂"));
        assertTrue(!systemPrompt.contains("不要输出“我来查询”“让我搜索”"));
        assertTrue(systemPrompt.contains("纯中文文本"));
    }

    @Test
    void tryRunShouldIncludePreferenceContextInSimpleTaskPrompt() {
        AgentMocks mocks = agentMocks("继续推荐跑鞋");
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        when(mocks.requestSpec.user(userPromptCaptor.capture())).thenReturn(mocks.requestSpec);
        SimpleTaskAgent agent = agent(mocks, mock(BuiltInTools.class), null);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "FAQ_SIMPLE_QUERY",
                Map.of(),
                Map.of("category", "跑鞋"),
                false,
                0.92,
                "知识库简单查询"
        );

        FastLaneResult result = agent.tryRun(
                route,
                "再推荐几双",
                "session-1",
                0.7,
                "当前会话短期导购偏好：\n- 品类：跑鞋"
        );

        assertTrue(result.handled());
        String prompt = userPromptCaptor.getValue();
        assertTrue(prompt.contains("当前会话短期导购偏好"));
        assertTrue(prompt.contains("品类：跑鞋"));
        assertTrue(prompt.contains("用户本轮输入："));
        assertTrue(prompt.contains("再推荐几双"));
    }

    @Test
    void tryRunShouldCaptureSimpleTaskInputAndOutput() {
        AgentMocks mocks = agentMocks("轻量跑步鞋适合日常慢跑。");
        RecordingTracing tracing = new RecordingTracing();
        SimpleTaskAgent agent = agent(mocks, mock(BuiltInTools.class), null, tracing);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "FAQ_SIMPLE_QUERY",
                Map.of(),
                Map.of("category", "跑鞋"),
                false,
                0.92,
                "知识库简单查询"
        );

        FastLaneResult result = agent.tryRun(route, "推荐跑鞋", "session-1", 0.7);

        assertTrue(result.handled());
        assertTrue(tracing.text("llm.simple_task.input").contains("system:"));
        assertTrue(tracing.text("llm.simple_task.input").contains("user:"));
        assertTrue(tracing.text("llm.simple_task.input").contains("推荐跑鞋"));
        assertEquals("轻量跑步鞋适合日常慢跑。", tracing.text("llm.simple_task.output"));
    }

    @Test
    void shouldRunMallTaskWithOnlySimpleMallTools() {
        AgentMocks mocks = agentMocks("儿童积木套装 300片售价 149.00 元，库存充足。");
        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        when(mocks.requestSpec.system(systemPromptCaptor.capture())).thenReturn(mocks.requestSpec);
        ToolCallbackProvider mallProvider = providerWithTools(
                "mall_search_products",
                "mall_get_product_detail",
                "mall_add_to_cart",
                "mall_view_cart",
                "mall_prepare_order",
                "mall_create_order"
        );
        SimpleTaskAgent agent = agent(mocks, null, List.of(mallProvider));
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "SIMPLE_SHOPPING_TOOL",
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
        assertTrue(systemPromptCaptor.getValue().contains("ok=false"));
        assertTrue(systemPromptCaptor.getValue().contains("导购推断"));
        assertTrue(systemPromptCaptor.getValue().contains("工具结果没有明确写出"));
        assertTrue(systemPromptCaptor.getValue().contains("适配/推荐类问题必须标注推断"));
        assertTrue(!systemPromptCaptor.getValue().contains("推荐、选哪个、更合适、别太复杂"));
        assertTrue(!systemPromptCaptor.getValue().contains("不要输出“我来查询”“让我搜索”"));
        assertTrue(systemPromptCaptor.getValue().contains("调用完成前不要输出任何可见文字"));
        assertTrue(systemPromptCaptor.getValue().contains("涉及建议时用“工具结果事实”“导购推断”"));
        verify(mocks.requestSpec).toolContext(Map.of("sessionId", "session-1"));
    }

    @Test
    void shouldWrapSimpleMallToolsWithTracingCallback() {
        AgentMocks mocks = agentMocks("购物车为空。");
        ToolCallbackProvider mallProvider = providerWithTools("mall_view_cart");
        RecordingTracing tracing = new RecordingTracing();
        SimpleTaskAgent agent = agent(mocks, null, List.of(mallProvider), tracing);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CART_CONFIRMATION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                false,
                0.95,
                "查购物车"
        );

        FastLaneResult result = agent.tryRun(route, "查看购物车", "session-1", 0.7);

        assertTrue(result.handled());
        List<ToolCallback> callbacks = capturedToolCallbacks(mocks);
        assertEquals(1, callbacks.size());
        assertTrue(callbacks.get(0) instanceof LoggingToolCallback);
    }

    @Test
    void knowledgeToolShouldRecordSuccessfulSearchInToolMemory() {
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        SimpleTaskAgent.KnowledgeFastLaneTools tools = new SimpleTaskAgent.KnowledgeFastLaneTools(
                builtInTools,
                toolMemory,
                "app-user",
                "session-1"
        );
        when(builtInTools.searchProductKnowledge("儿童积木"))
                .thenReturn("儿童积木适合 3 岁以上儿童，主打益智启蒙。");

        String result = tools.searchProductKnowledge("儿童积木");

        assertEquals("儿童积木适合 3 岁以上儿童，主打益智启蒙。", result);
        ConversationToolCallRecord record = toolMemory.records("app-user", "session-1").get(0);
        assertEquals("searchProductKnowledge", record.toolName());
        assertEquals("儿童积木", record.input());
        assertEquals("儿童积木适合 3 岁以上儿童，主打益智启蒙。", record.output());
        assertEquals(ConversationToolCallRecord.Status.OK, record.status());
    }

    @Test
    void knowledgeToolShouldRecordErrorWhenRagThrowsWithoutRawExceptionMessage() {
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        SimpleTaskAgent.KnowledgeFastLaneTools tools = new SimpleTaskAgent.KnowledgeFastLaneTools(
                builtInTools,
                toolMemory,
                "app-user",
                "session-1"
        );
        when(builtInTools.searchProductKnowledge("儿童积木"))
                .thenThrow(new RuntimeException("backend exploded token=secret-token query={\"skuId\":3020}"));

        assertThrows(SimpleTaskAgent.FastLaneFallbackException.class,
                () -> tools.searchProductKnowledge("儿童积木"));

        ConversationToolCallRecord record = toolMemory.records("app-user", "session-1").get(0);
        assertEquals("searchProductKnowledge", record.toolName());
        assertEquals("儿童积木", record.input());
        assertEquals(ConversationToolCallRecord.Status.ERROR, record.status());
        assertEquals("FastLaneFallbackException", record.errorType());
        assertFalse(record.output().contains("backend exploded"));
        String context = toolMemory.recentToolCallContext("app-user", "session-1");
        assertFalse(context.contains("backend exploded"));
        assertFalse(context.contains("secret-token"));
        assertFalse(context.contains("\"skuId\":3020"));
    }

    @Test
    void shouldFallbackToCoreWhenKnowledgeToolReturnsEmpty() {
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        SimpleTaskAgent.KnowledgeFastLaneTools tools = new SimpleTaskAgent.KnowledgeFastLaneTools(
                builtInTools,
                toolMemory,
                "app-user",
                "session-1"
        );
        when(builtInTools.searchProductKnowledge("未知商品"))
                .thenReturn("商品知识库中没有检索到相关内容。");

        assertThrows(SimpleTaskAgent.FastLaneFallbackException.class,
                () -> tools.searchProductKnowledge("未知商品"));
        ConversationToolCallRecord record = toolMemory.records("app-user", "session-1").get(0);
        assertEquals("searchProductKnowledge", record.toolName());
        assertEquals("未知商品", record.input());
        assertEquals(ConversationToolCallRecord.Status.ERROR, record.status());
        assertEquals("FastLaneFallbackException", record.errorType());
        assertFalse(record.output().contains("未知商品"));
    }

    @Test
    void simpleMallToolCallbackShouldRecordToolMemoryWithAppUserAndKeepMallAuthContext() {
        ConversationToolCallMemoryService toolMemory = new ConversationToolCallMemoryService();
        AgentMocks mocks = agentMocks("儿童积木套装 300片售价 149.00 元。");
        ToolCallbackProvider mallProvider = providerWithTools("mall_get_product_detail");
        SimpleTaskAgent agent = agent(mocks, null, List.of(mallProvider), new RagTracing(), toolMemory);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木套装 300片"),
                false,
                0.95,
                "查商品详情"
        );

        FastLaneResult result = agent.tryRun(
                route,
                "儿童积木套装 300片详情",
                "app-user",
                "session-1",
                0.7,
                "",
                "Bearer mall-token",
                "mall-user",
                "mall-password"
        );

        assertTrue(result.handled());
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mocks.requestSpec).toolContext(contextCaptor.capture());
        Map<String, Object> toolContext = contextCaptor.getValue();
        assertEquals("app-user", toolContext.get("userId"));
        assertEquals("session-1", toolContext.get("sessionId"));
        assertEquals("Bearer mall-token", toolContext.get("mallToken"));
        assertEquals("mall-user", toolContext.get("mallUsername"));
        assertEquals("mall-password", toolContext.get("mallPassword"));

        capturedToolCallbacks(mocks).get(0)
                .call("{\"skuId\":3020}", new ToolContext(toolContext));

        assertTrue(toolMemory.records("mall-user", "session-1").isEmpty());
        ConversationToolCallRecord record = toolMemory.records("app-user", "session-1").get(0);
        assertEquals("mall_get_product_detail", record.toolName());
        assertEquals("{\"skuId\":3020}", record.input());
        assertEquals(ConversationToolCallRecord.Status.OK, record.status());
        assertTrue(record.output().contains("工具结果事实"));
    }

    @Test
    void shouldReturnGenericMcpFailureWhenMallClientIsUnavailable() {
        AgentMocks mocks = agentMocks("不会返回");
        SimpleTaskAgent agent = agent(mocks, null, List.of());
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CART_CONFIRMATION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                false,
                0.9,
                "查购物车"
        );

        FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

        assertTrue(result.handled());
        assertEquals("商城 MCP 调用失败：请稍后重试", collect(result.stream()));
    }

    @Test
    void shouldReturnGenericMcpFailureWhenMallToolsAreMissing() {
        AgentMocks mocks = agentMocks("不会返回");
        SimpleTaskAgent agent = agent(mocks, null, List.of(providerWithTools()));
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CART_CONFIRMATION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                false,
                0.9,
                "查购物车"
        );

        FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

        assertTrue(result.handled());
        assertEquals("商城 MCP 调用失败：请稍后重试", collect(result.stream()));
    }

    @Test
    void shouldReturnMcpFailureDirectlyWhenSimpleModelCallSeesMcpUnavailable() {
        AgentMocks mocks = agentMocks("不会返回");
        when(mocks.requestSpec.call()).thenThrow(new SimpleTaskAgent.McpUnavailableException("mall-mcp 服务未启动或不可访问"));
        SimpleTaskAgent agent = agent(mocks, null, List.of(providerWithTools("mall_view_cart")));
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CART_CONFIRMATION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                false,
                0.9,
                "查购物车"
        );

        FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

        assertTrue(result.handled());
        assertEquals("商城 MCP 调用失败：请稍后重试", collect(result.stream()));
    }

    @Test
    void shouldReturnMcpFailureDirectlyWhenMallToolExecutionFails() {
        AgentMocks mocks = agentMocks("不会返回");
        ToolExecutionException exception = new ToolExecutionException(
                toolDefinition("mall_view_cart"),
                new RuntimeException("mcp call failed")
        );
        when(mocks.requestSpec.call()).thenThrow(exception);
        SimpleTaskAgent agent = agent(mocks, null, List.of(providerWithTools("mall_view_cart")));
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CART_CONFIRMATION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                false,
                0.9,
                "查购物车"
        );

        FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

        assertTrue(result.handled());
        assertEquals("商城 MCP 调用失败：请稍后重试", collect(result.stream()));
    }

    @Test
    void shouldNotExposeMcpFailureDetailsToUserOrInfoLogs() {
        try (CapturedLogs capturedLogs = new CapturedLogs()) {
            AgentMocks mocks = agentMocks("不会返回");
            when(mocks.requestSpec.call()).thenThrow(new SimpleTaskAgent.McpUnavailableException(
                    "mall-mcp 服务未启动或不可访问 token=secret-token input={\"skuId\":3020}"));
            SimpleTaskAgent agent = agent(mocks, null, List.of(providerWithTools("mall_view_cart")));
            ShoppingIntentRoute route = new ShoppingIntentRoute(
                    "CART_CONFIRMATION",
                    "SIMPLE_SHOPPING_TOOL",
                    Map.of(),
                    Map.of(),
                    false,
                    0.9,
                    "查购物车"
            );

            FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

            assertTrue(result.handled());
            assertEquals("商城 MCP 调用失败：请稍后重试", collect(result.stream()));
            String logs = capturedLogs.formattedMessages();
            assertTrue(logs.contains("errorType=McpUnavailableException"));
            assertFalse(logs.contains("secret-token"));
            assertFalse(logs.contains("\"skuId\":3020"));
        }
    }

    @Test
    void shouldKeepToolDiscoveryFailureCauseOnlyInDebugLogs() {
        try (CapturedLogs capturedLogs = new CapturedLogs(Level.DEBUG)) {
            AgentMocks mocks = agentMocks("不会返回");
            ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
            when(provider.getToolCallbacks()).thenThrow(new RuntimeException(
                    "discovery failed token=secret-token input={\"skuId\":3020}"));
            SimpleTaskAgent agent = agent(mocks, null, List.of(provider));
            ShoppingIntentRoute route = new ShoppingIntentRoute(
                    "CART_CONFIRMATION",
                    "SIMPLE_SHOPPING_TOOL",
                    Map.of(),
                    Map.of(),
                    false,
                    0.9,
                    "查购物车"
            );

            FastLaneResult result = agent.tryRun(route, "查看我的购物车", "session-1", 0.7);

            assertTrue(result.handled());
            assertEquals("商城 MCP 调用失败：请稍后重试", collect(result.stream()));
            String logs = capturedLogs.formattedMessages();
            assertTrue(logs.contains("errorType=McpUnavailableException"));
            assertFalse(logs.contains("secret-token"));
            assertFalse(logs.contains("\"skuId\":3020"));
            String debugThrowableMessages = capturedLogs.debugThrowableMessages();
            assertTrue(debugThrowableMessages.contains(
                    "java.lang.RuntimeException: discovery failed token=secret-token input={\"skuId\":3020}"));
        }
    }

    private AgentMocks agentMocks(String content) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient.Builder clonedBuilder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(builder.clone()).thenReturn(clonedBuilder);
        when(clonedBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);
        return new AgentMocks(builder, requestSpec);
    }

    private SimpleTaskAgent agent(AgentMocks mocks,
                                  BuiltInTools builtInTools,
                                  List<ToolCallbackProvider> providers) {
        return agent(mocks, builtInTools, providers, new RagTracing());
    }

    private SimpleTaskAgent agent(AgentMocks mocks,
                                  BuiltInTools builtInTools,
                                  List<ToolCallbackProvider> providers,
                                  RagTracing tracing) {
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.builder, builtInTools, providers, tracing);
        agent.init();
        return agent;
    }

    private SimpleTaskAgent agent(AgentMocks mocks,
                                  BuiltInTools builtInTools,
                                  List<ToolCallbackProvider> providers,
                                  RagTracing tracing,
                                  ConversationToolCallMemoryService toolMemory) {
        SimpleTaskAgent agent = new SimpleTaskAgent(mocks.builder, builtInTools, providers, tracing, toolMemory);
        agent.init();
        return agent;
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

    private ToolCallbackProvider providerWithTools(String... toolNames) {
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        ToolCallback[] callbacks = Arrays.stream(toolNames)
                .map(this::toolCallback)
                .toArray(ToolCallback[]::new);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return provider;
    }

    private ToolCallback toolCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = toolDefinition(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any())).thenReturn("{\"ok\":true}");
        return callback;
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

    private record AgentMocks(ChatClient.Builder builder, ChatClient.ChatClientRequestSpec requestSpec) {
    }

    private static final class CapturedLogs implements AutoCloseable {

        private final Logger logger = (Logger) LoggerFactory.getLogger(SimpleTaskAgent.class);
        private final Level originalLevel = logger.getLevel();
        private final boolean originalAdditive = logger.isAdditive();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedLogs() {
            this(Level.INFO);
        }

        private CapturedLogs(Level level) {
            logger.setLevel(level);
            logger.setAdditive(false);
            appender.start();
            logger.addAppender(appender);
        }

        private String formattedMessages() {
            return appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
        }

        private String debugThrowableMessages() {
            return appender.list.stream()
                    .filter(event -> event.getLevel().equals(Level.DEBUG))
                    .flatMap(event -> throwableMessages(event.getThrowableProxy()).stream())
                    .collect(Collectors.joining("\n"));
        }

        private List<String> throwableMessages(IThrowableProxy throwableProxy) {
            List<String> messages = new ArrayList<>();
            IThrowableProxy current = throwableProxy;
            while (current != null) {
                messages.add(current.getClassName() + ": " + current.getMessage());
                current = current.getCause();
            }
            return messages;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }
    }

    private static final class RecordingTracing extends RagTracing {

        private final Map<String, String> captured = new LinkedHashMap<>();

        @Override
        public Span currentSpan() {
            return Span.getInvalid();
        }

        @Override
        public void capturePromptText(Span span, String key, String value) {
            captured.put(key, value);
        }

        private String text(String key) {
            return captured.getOrDefault(key, "");
        }
    }
}
