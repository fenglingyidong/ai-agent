package com.example.ragagent.agent;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.mall.MallMcpClient;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.service.ChatModelRegistry;
import com.example.ragagent.service.ReActAgent;
import com.example.ragagent.tools.BuiltInTools;
import io.modelcontextprotocol.client.McpSyncClient;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assertEquals(Set.of("searchProductKnowledge", "updateShoppingPreference"), toolNames);
        String systemPrompt = systemPromptCaptor.getValue();
        assertTrue(systemPrompt.contains("知识库原文事实"));
        assertTrue(systemPrompt.contains("导购推断"));
        assertTrue(systemPrompt.contains("知识库未明确"));
        assertTrue(systemPrompt.contains("不得省略"));
        assertTrue(systemPrompt.contains("推荐、选哪个、更合适、别太复杂"));
        assertTrue(systemPrompt.contains("不要输出“我来查询”“让我搜索”"));
        assertTrue(systemPrompt.contains("调用完成前不要输出任何可见文字"));
        assertTrue(systemPrompt.contains("必须使用“知识库原文事实”“导购推断”两个小节"));
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
