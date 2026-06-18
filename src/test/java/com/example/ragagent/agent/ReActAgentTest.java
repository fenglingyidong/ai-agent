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
import com.example.ragagent.tools.BuiltInTools;
import io.modelcontextprotocol.client.McpSyncClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentTest {

    @Test
    void runShouldRegisterBuiltInToolsAndUseMemoryAdvisors() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        List<List<Message>> messageSnapshots = new ArrayList<>();
        List<ToolCallback> registeredCallbacks = new ArrayList<>();
        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);

        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(systemPromptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenAnswer(invocation -> {
            registeredCallbacks.clear();
            registeredCallbacks.addAll(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            messageSnapshots.add(List.copyOf(messages));
            return requestSpec;
        });
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("4"));

        ReActAgent agent = newAgent(reactChatClient, builtInTools, memory);

        String result = collect(agent.runStream("user-1", "session-1", null, "What is 2 + 2?", false,
                List.of(), "", "", ""));

        assertEquals("4", result);
        verify(requestSpec).advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any());
        verify(memory.conversationMemoryService(), never()).rememberTurn(anyString(), anyString(), anyString(), anyString());
        assertEquals(1, messageSnapshots.size());
        assertSecuredUserMessage(messageSnapshots.get(0).get(0), "What is 2 + 2?");

        Set<String> toolNames = registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertEquals(Set.of("searchProductKnowledge"), toolNames);
        String systemPrompt = systemPromptCaptor.getValue();
        assertTrue(systemPrompt.contains("核心规则"));
        assertTrue(systemPrompt.contains("封闭商品池"));
        assertTrue(systemPrompt.contains("至少引用 1 个候选商品"));
        assertTrue(systemPrompt.contains("候选外商品"));
        assertTrue(systemPrompt.contains("商城实时详情补强"));
        assertTrue(systemPrompt.contains("工具调用完成前不要输出可见文字"));
        assertTrue(systemPrompt.contains("不要编造"));
        assertTrue(systemPrompt.contains("为什么这么回答"));
        assertTrue(!systemPrompt.contains("先调用 searchProductKnowledge"));
        assertTrue(!systemPrompt.contains("可基于已知知识和用户条件直接回答"));
        assertTrue(systemPrompt.length() < 900);
    }

    @Test
    void runShouldSendPromptThroughDirectChatModelWithToolsAndContext() {
        ChatModel chatModel = mock(ChatModel.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.stream(promptCaptor.capture())).thenReturn(Flux.just(chatResponse("4")));

        ReActAgent agent = new ReActAgent(
                null,
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

        String result = collect(agent.runStream("user-1", "session-1", null, "What is 2 + 2?", false,
                List.of(), "", "", ""));

        assertEquals("4", result);
        Prompt prompt = promptCaptor.getValue();
        assertInstanceOf(SystemMessage.class, prompt.getSystemMessage());
        assertSecuredUserMessage(prompt.getUserMessage(), "What is 2 + 2?");
        OpenAiChatOptions options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
        assertEquals("user-1", options.getToolContext().get("userId"));
        assertEquals("session-1", options.getToolContext().get("sessionId"));
        assertTrue(options.getToolContext().containsKey(BuiltInTools.TOOL_CONTEXT_SEARCH_PRODUCT_KNOWLEDGE_CACHE));
        assertTrue(options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("searchProductKnowledge"::equals));
        verify(memory.longTermMemoryAdvisor()).before(any(), any());
    }

    @Test
    void runStreamShouldCaptureReactInputAndOutput() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        RecordingTracing tracing = new RecordingTracing();

        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("推荐 ", "轻量跑步鞋"));

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
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
                tracing
        );

        String result = collect(agent.runStream("user-1", "session-1", null, "推荐跑鞋", false,
                List.of(), "", "", ""));

        assertEquals("推荐 轻量跑步鞋", result);
        assertTrue(tracing.text("llm.react.input").contains("system:"));
        assertTrue(tracing.text("llm.react.input").contains("user:"));
        assertTrue(tracing.text("llm.react.input").contains("推荐跑鞋"));
        assertTrue(tracing.text("llm.react.input").contains("searchProductKnowledge"));
        assertEquals("推荐 轻量跑步鞋", tracing.text("llm.react.output"));
    }

    @Test
    void runStreamShouldStartReactSpanUnderRequestRootSpanAfterSubscriptionContextLoss() {
        ChatModel chatModel = mock(ChatModel.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        RecordingTracing tracing = new RecordingTracing();

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("ok")));

        ReActAgent agent = new ReActAgent(
                null,
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
        assertSame(tracing.rootSpan(), tracing.parentWhenStarted("llm.react"));
    }

    @Test
    void runShouldMergeExternalToolCallbacksWhenWebSearchEnabled() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        ToolCallbackProvider externalProvider = mock(ToolCallbackProvider.class);
        ToolCallback externalCallback = mock(ToolCallback.class);
        ToolDefinition externalDefinition = mock(ToolDefinition.class);
        List<ToolCallback> registeredCallbacks = new ArrayList<>();

        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(reactChatClient);
        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenAnswer(invocation -> {
            registeredCallbacks.clear();
            registeredCallbacks.addAll(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("latest ", "answer"));
        when(externalProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{externalCallback});
        when(externalCallback.getToolDefinition()).thenReturn(externalDefinition);
        when(externalDefinition.name()).thenReturn("webSearch");

        ReActAgent agent = new ReActAgent(
                builder,
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                null,
                null,
                List.of(externalProvider),
                mock(ConversationLogService.class)
        );

        String result = collect(agent.runStream("user-web", "session-web", null, "today news", true,
                List.of(), "", "", ""));

        assertEquals("latest answer", result);
        assertTrue(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("webSearch"::equals));
    }

    @Test
    void runStreamShouldApplySelectedModelToReasoningCall() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        ChatModelRegistry chatModelRegistry = mock(ChatModelRegistry.class);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("deepseek-v3.2").build();

        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(options)).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("I am ", "DeepSeek."));
        when(chatModelRegistry.createOptions("deepseek")).thenReturn(options);

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                chatModelRegistry,
                null,
                List.of(),
                mock(ConversationLogService.class)
        );

        String result = collect(agent.runStream("user-4", "session-4", "deepseek", "Who are you?", false,
                List.of(), "", "", ""));

        assertEquals("I am DeepSeek.", result);
        verify(chatModelRegistry).createOptions("deepseek");
        verify(requestSpec).options(options);
    }

    @Test
    void runStreamShouldAttachMediaToCurrentUserMessage() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();
        List<List<Message>> messageSnapshots = new ArrayList<>();
        Media media = new Media(MediaType.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2, 3}));

        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            messageSnapshots.add(List.copyOf(messages));
            return requestSpec;
        });
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("image answer"));

        ReActAgent agent = newAgent(reactChatClient, builtInTools, memory);

        String result = collect(agent.runStream("user-img", "session-img", null, "find similar", false,
                List.of(media), "", "", ""));

        assertEquals("image answer", result);
        UserMessage userMessage = assertInstanceOf(UserMessage.class, messageSnapshots.get(0).get(0));
        assertEquals(1, userMessage.getMedia().size());
        assertEquals(MediaType.IMAGE_PNG, userMessage.getMedia().get(0).getMimeType());
    }

    @Test
    void runStreamShouldRememberFallbackWhenModelStreamFails() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        MemoryTestSupport memory = memorySupport();

        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.error(new RuntimeException("Connection reset")));

        ReActAgent agent = newAgent(reactChatClient, builtInTools, memory);

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
        ChatClient reactChatClient = mock(ChatClient.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        LongTermMemoryAdvisor longTermMemoryAdvisor = mock(LongTermMemoryAdvisor.class);
        MessageChatMemoryAdvisor messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build()
        ).build();
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        MallMcpClient mallMcpClient = mock(MallMcpClient.class);
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(mallMcpClient.syncClient()).thenReturn(syncClient);
        when(syncClient.listTools()).thenThrow(new IllegalStateException("mall-mcp 服务未启动或不可访问"));
        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                null,
                List.of(),
                mallMcpClient,
                mock(ConversationLogService.class)
        );

        String result = collect(agent.runStream("user-mall", "session-mall", null, "查一下库存", false,
                List.of(), "", "", ""));

        assertEquals("商城 MCP 调用失败：mall-mcp 服务未启动或不可访问", result);
        verify(reactChatClient, never()).prompt();
        verify(conversationMemoryService).rememberTurn(
                "user-mall",
                "session-mall",
                secure("查一下库存").modelInput(),
                result
        );
    }

    private void assertSecuredUserMessage(Message message, String expectedUserText) {
        String text = assertInstanceOf(UserMessage.class, message).getText();
        assertEquals(secure(expectedUserText).modelInput(), text);
    }

    private ReActAgent newAgent(ChatClient reactChatClient, BuiltInTools builtInTools, MemoryTestSupport memory) {
        return new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                memory.longTermMemoryAdvisor(),
                memory.messageChatMemoryAdvisor(),
                memory.conversationMemoryService(),
                new PromptSecurityFilter(),
                null,
                null,
                List.of(),
                mock(ConversationLogService.class)
        );
    }

    private MemoryTestSupport memorySupport() {
        LongTermMemoryAdvisor longTermMemoryAdvisor = mock(LongTermMemoryAdvisor.class);
        MessageChatMemoryAdvisor messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build()
        ).build();
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        when(conversationMemoryService.buildConversationId(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "::" + invocation.getArgument(1));
        return new MemoryTestSupport(longTermMemoryAdvisor, messageChatMemoryAdvisor, conversationMemoryService);
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

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
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

        @Override
        public Span currentSpan() {
            return currentSpan.get();
        }

        @Override
        public Span startSpan(String spanName) {
            parentWhenStarted.put(spanName, currentSpan.get());
            return mock(Span.class);
        }

        @Override
        public Scope makeCurrent(Span span) {
            Span previous = currentSpan.get();
            currentSpan.set(span);
            return () -> currentSpan.set(previous);
        }

        @Override
        public void endSpan(Span span) {
        }

        @Override
        public void capturePromptText(Span span, String key, String value) {
            captured.put(key, value);
        }

        private String text(String key) {
            return captured.getOrDefault(key, "");
        }

        private Span rootSpan() {
            return rootSpan;
        }

        private Span parentWhenStarted(String spanName) {
            return parentWhenStarted.get(spanName);
        }

        private void clearCurrentSpan() {
            currentSpan.set(Span.getInvalid());
        }
    }
}
