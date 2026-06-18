package com.example.ragagent.agent;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.mall.MallMcpClient;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.prompt.PromptTemplateStore;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.service.ChatModelRegistry;
import com.example.ragagent.service.ReActAgent;
import com.example.ragagent.testsupport.RecordingChatModel;
import com.example.ragagent.tools.BuiltInTools;
import io.modelcontextprotocol.client.McpSyncClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentTest {

    @Test
    void runStreamShouldSendPromptToChatModelWithToolsAndMemoryAdvisors() {
        RecordingChatModel chatModel = RecordingChatModel.responding("4");
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();

        ReActAgent agent = newAgent(chatModel, builtInTools, memory);

        String result = collect(agent.runStream("user-1", "session-1", null, "What is 2 + 2?", false,
                List.of(), "", "", ""));

        assertEquals("4", result);
        verify(memory.longTermMemoryAdvisor()).before(any(), any());
        Prompt prompt = chatModel.lastPrompt();
        assertSecuredUserMessage(prompt.getUserMessage(), "What is 2 + 2?");
        assertTrue(prompt.getSystemMessage().getText().contains("核心规则"));

        OpenAiChatOptions options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
        assertTrue(Boolean.TRUE.equals(options.getStreamUsage()));
        assertEquals("user-1", options.getToolContext().get("userId"));
        assertEquals("session-1", options.getToolContext().get("sessionId"));
        Set<String> toolNames = options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertEquals(Set.of("searchProductKnowledge"), toolNames);
    }

    @Test
    void runStreamShouldCaptureReactInputAndOutput() {
        RecordingChatModel chatModel = RecordingChatModel.responding("推荐 ", "轻量跑步鞋");
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        RecordingTracing tracing = new RecordingTracing();

        ReActAgent agent = new ReActAgent(
                chatModel,
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                null,
                null,
                List.of(),
                null,
                mock(ConversationLogService.class),
                tracing,
                new PromptTemplateStore()
        );

        String result = collect(agent.runStream("user-1", "session-1", null, "推荐跑鞋", false,
                List.of(), "", "", ""));

        assertEquals("推荐 轻量跑步鞋", result);
        assertTrue(tracing.text("llm.react.input").contains("system:"));
        assertTrue(tracing.text("llm.react.input").contains("user:"));
        assertTrue(tracing.text("llm.react.input").contains("推荐跑鞋"));
        assertTrue(tracing.text("llm.react.input").contains("searchProductKnowledge"));
        assertEquals("推荐 轻量跑步鞋", tracing.text("llm.react.output"));
        Span requestSpan = tracing.span("POST /api/react");
        assertEquals("推荐跑鞋", tracing.traceValue(requestSpan, "langfuse.trace.input"));
        assertEquals("推荐 轻量跑步鞋", tracing.traceValue(requestSpan, "langfuse.trace.output"));
        assertTrue(tracing.ended("POST /api/react"));
    }

    @Test
    void runStreamShouldStartReactSpanUnderRequestRootSpanAfterSubscriptionContextLoss() {
        RecordingChatModel chatModel = RecordingChatModel.responding("ok");
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        RecordingTracing tracing = new RecordingTracing();
        ReActAgent agent = new ReActAgent(
                chatModel,
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                null,
                null,
                List.of(),
                null,
                mock(ConversationLogService.class),
                tracing,
                new PromptTemplateStore()
        );

        Flux<String> stream = agent.runStream("user-1", "session-1", null, "推荐跑鞋", false,
                List.of(), "", "", "");
        tracing.clearCurrentSpan();

        assertEquals("ok", collect(stream));
        assertSame(tracing.rootSpan(), tracing.parentWhenStarted("POST /api/react"));
        assertSame(tracing.span("POST /api/react"), tracing.parentWhenStarted("llm.react"));
    }

    @Test
    void runStreamShouldMergeExternalToolCallbacksWhenWebSearchEnabled() {
        RecordingChatModel chatModel = RecordingChatModel.responding("latest ", "answer");
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        ToolCallbackProvider externalProvider = mock(ToolCallbackProvider.class);
        ToolCallback externalCallback = mock(ToolCallback.class);
        ToolDefinition externalDefinition = mock(ToolDefinition.class);
        when(externalProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{externalCallback});
        when(externalCallback.getToolDefinition()).thenReturn(externalDefinition);
        when(externalDefinition.name()).thenReturn("webSearch");
        ReActAgent agent = new ReActAgent(
                chatModel,
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                null,
                null,
                List.of(externalProvider),
                null,
                mock(ConversationLogService.class),
                new RagTracing(),
                new PromptTemplateStore()
        );

        String result = collect(agent.runStream("user-web", "session-web", null, "today news", true,
                List.of(), "", "", ""));

        assertEquals("latest answer", result);
        OpenAiChatOptions options = assertInstanceOf(OpenAiChatOptions.class, chatModel.lastPrompt().getOptions());
        assertTrue(options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("webSearch"::equals));
    }

    @Test
    void runStreamShouldApplySelectedModelToReasoningCall() {
        RecordingChatModel chatModel = RecordingChatModel.responding("I am ", "DeepSeek.");
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        ChatModelRegistry chatModelRegistry = mock(ChatModelRegistry.class);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("deepseek-v3.2").build();
        when(chatModelRegistry.createOptions("deepseek")).thenReturn(options);

        ReActAgent agent = new ReActAgent(
                chatModel,
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                chatModelRegistry,
                null,
                List.of(),
                null,
                mock(ConversationLogService.class),
                new RagTracing(),
                new PromptTemplateStore()
        );

        String result = collect(agent.runStream("user-4", "session-4", "deepseek", "Who are you?", false,
                List.of(), "", "", ""));

        assertEquals("I am DeepSeek.", result);
        verify(chatModelRegistry).createOptions("deepseek");
        OpenAiChatOptions promptOptions = assertInstanceOf(OpenAiChatOptions.class, chatModel.lastPrompt().getOptions());
        assertEquals(options.getModel(), promptOptions.getModel());
        assertTrue(Boolean.TRUE.equals(promptOptions.getStreamUsage()));
    }

    @Test
    void runStreamShouldAttachMediaToCurrentUserMessage() {
        RecordingChatModel chatModel = RecordingChatModel.responding("image answer");
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        Media media = new Media(MediaType.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2, 3}));
        ReActAgent agent = newAgent(chatModel, builtInTools, memory);

        String result = collect(agent.runStream("user-img", "session-img", null, "find similar", false,
                List.of(media), "", "", ""));

        assertEquals("image answer", result);
        UserMessage userMessage = assertInstanceOf(UserMessage.class, chatModel.lastPrompt().getUserMessage());
        assertEquals(1, userMessage.getMedia().size());
        assertEquals(MediaType.IMAGE_PNG, userMessage.getMedia().get(0).getMimeType());
    }

    @Test
    void runStreamShouldRememberFallbackWhenModelStreamFails() {
        RecordingChatModel chatModel = new RecordingChatModel()
                .thenStream(Flux.error(new RuntimeException("Connection reset")));
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        ReActAgent agent = newAgent(chatModel, builtInTools, memory);

        String result = collect(agent.runStream("user-reset", "session-reset", null, "price?", false,
                List.of(), "", "", ""));

        assertTrue(result.contains("大模型服务连接被中断") || result.contains("妯″瀷鏈嶅姟杩炴帴"));
        verify(memory.conversationMemoryService()).rememberTurn(
                "user-reset",
                "session-reset",
                secure("price?").modelInput(),
                result
        );
    }

    @Test
    void runStreamShouldReturnMallMcpFailureWhenMallToolDiscoveryFails() {
        RecordingChatModel chatModel = RecordingChatModel.responding("unused");
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        LongTermMemoryAdvisor longTermMemoryAdvisor = mock(LongTermMemoryAdvisor.class);
        MessageChatMemoryAdvisor messageChatMemoryAdvisor = memoryAdvisor();
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(syncClient.listTools()).thenThrow(new IllegalStateException("mall-mcp 服务未启动或不可访问"));
        ReActAgent agent = new ReActAgent(
                chatModel,
                builtInTools,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                null,
                List.of(),
                mallMcpClient,
                mock(ConversationLogService.class),
                new RagTracing(),
                new PromptTemplateStore()
        );

        String result = collect(agent.runStream("user-mall", "session-mall", null, "查一下库存", false,
                List.of(), "", "", ""));

        assertEquals("商城 MCP 调用失败：mall-mcp 服务未启动或不可访问", result);
        assertEquals(0, chatModel.prompts().size());
        verify(conversationMemoryService).rememberTurn(
                "user-mall",
                "session-mall",
                secure("查一下库存").modelInput(),
                result
        );
    }

    private void assertSecuredUserMessage(Object message, String expectedUserText) {
        String text = assertInstanceOf(UserMessage.class, message).getText();
        assertEquals(secure(expectedUserText).modelInput(), text);
    }

    private ReActAgent newAgent(ChatModel chatModel, BuiltInTools builtInTools, MemoryTestSupport memory) {
        return new ReActAgent(
                chatModel,
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                null,
                null,
                List.of(),
                null,
                mock(ConversationLogService.class),
                new RagTracing(),
                new PromptTemplateStore()
        );
    }

    private MemoryTestSupport memorySupport() {
        LongTermMemoryAdvisor longTermMemoryAdvisor = mock(LongTermMemoryAdvisor.class);
        MessageChatMemoryAdvisor messageChatMemoryAdvisor = memoryAdvisor();
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        when(conversationMemoryService.buildConversationId(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "::" + invocation.getArgument(1));
        return new MemoryTestSupport(longTermMemoryAdvisor, messageChatMemoryAdvisor, conversationMemoryService);
    }

    private MessageChatMemoryAdvisor memoryAdvisor() {
        return MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build()
        ).build();
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    private PromptSecurityFilter.SecuredPrompt secure(String userText) {
        return new PromptSecurityFilter().secure(userText);
    }

    private record MemoryTestSupport(
            LongTermMemoryAdvisor longTermMemoryAdvisor,
            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            ConversationMemoryService conversationMemoryService
    ) {
    }

    private static final class RecordingTracing extends RagTracing {

        private final Map<String, String> captured = new LinkedHashMap<>();
        private final Span rootSpan = mock(Span.class);
        private final ThreadLocal<Span> currentSpan = ThreadLocal.withInitial(() -> rootSpan);
        private final Map<String, Span> parentWhenStarted = new LinkedHashMap<>();
        private final Map<String, Span> spansByName = new LinkedHashMap<>();
        private final Map<Span, Map<String, String>> traceValues = new LinkedHashMap<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public Span currentSpan() {
            return currentSpan.get();
        }

        @Override
        public Span startSpan(String spanName) {
            parentWhenStarted.put(spanName, currentSpan.get());
            Span span = mock(Span.class);
            spansByName.put(spanName, span);
            return span;
        }

        @Override
        public Scope makeCurrent(Span span) {
            Span previous = currentSpan.get();
            currentSpan.set(span);
            return () -> currentSpan.set(previous);
        }

        @Override
        public void endSpan(Span span) {
            events.add("end:" + spanName(span));
        }

        @Override
        public void capturePromptText(Span span, String key, String value) {
            captured.put(key, value);
        }

        @Override
        public void recordTraceInput(Span span, String value) {
            traceValues.computeIfAbsent(span, ignored -> new LinkedHashMap<>())
                    .put("langfuse.trace.input", value);
        }

        @Override
        public void recordTraceOutput(Span span, String value) {
            String spanName = spanName(span);
            assertTrue(!events.contains("end:" + spanName), "trace output must be written before span ends");
            traceValues.computeIfAbsent(span, ignored -> new LinkedHashMap<>())
                    .put("langfuse.trace.output", value);
        }

        private String text(String key) {
            return captured.getOrDefault(key, "");
        }

        private Span rootSpan() {
            return rootSpan;
        }

        private Span span(String spanName) {
            return spansByName.get(spanName);
        }

        private Span parentWhenStarted(String spanName) {
            return parentWhenStarted.get(spanName);
        }

        private void clearCurrentSpan() {
            currentSpan.set(Span.getInvalid());
        }

        private String traceValue(Span span, String key) {
            return traceValues.getOrDefault(span, Map.of()).getOrDefault(key, "");
        }

        private boolean ended(String spanName) {
            return events.contains("end:" + spanName);
        }

        private String spanName(Span span) {
            return spansByName.entrySet().stream()
                    .filter(entry -> entry.getValue() == span)
                    .findFirst()
                    .map(Map.Entry::getKey)
                    .orElse(span == rootSpan ? "root" : "unknown");
        }
    }
}
