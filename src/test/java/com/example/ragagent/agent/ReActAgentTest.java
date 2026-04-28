package com.example.ragagent.agent;

import com.example.ragagent.memory.HierarchicalMemoryAdvisor;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.service.ChatModelRegistry;
import com.example.ragagent.service.ReActAgent;
import com.example.ragagent.tools.BuiltInTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    void runShouldRegisterBuiltInToolsThroughSpringAiToolCallbacks() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient finalAnswerChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec reactRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        HierarchicalMemoryAdvisor memoryAdvisor = mock(HierarchicalMemoryAdvisor.class);
        List<List<Message>> historySnapshots = new ArrayList<>();
        List<ToolCallback> registeredCallbacks = new ArrayList<>();

        when(reactChatClient.prompt()).thenReturn(reactRequestSpec);
        when(reactRequestSpec.system(anyString())).thenReturn(reactRequestSpec);
        when(reactRequestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenAnswer(invocation -> {
            registeredCallbacks.clear();
            registeredCallbacks.addAll(invocation.getArgument(0));
            return reactRequestSpec;
        });
        when(reactRequestSpec.messages(anyList())).thenAnswer(invocation -> {
            List<Message> history = invocation.getArgument(0);
            historySnapshots.add(List.copyOf(history));
            return reactRequestSpec;
        });
        when(memoryAdvisor.loadMemoryContext("user-1", "session-1", "What is 2 + 2?"))
                .thenReturn(new HierarchicalMemoryAdvisor.MemoryContext(Collections.emptyList(), "", null));
        when(reactRequestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("4"));

        ReActAgent agent = new ReActAgent(reactChatClient, finalAnswerChatClient, builtInTools, memoryAdvisor);

        String result = agent.run("user-1", "session-1", "What is 2 + 2?");

        assertEquals("4", result);
        verify(memoryAdvisor).rememberFinalTurn(
                "user-1",
                "session-1",
                secure("What is 2 + 2?").modelInput(),
                "4"
        );
        verify(builtInTools, never()).calculator(anyString());

        assertEquals(1, historySnapshots.size());
        assertEquals(1, historySnapshots.get(0).size());
        assertSecuredUserMessage(historySnapshots.get(0).get(0), "What is 2 + 2?");

        Set<String> toolNames = registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertEquals(Set.of("calculator", "searchKnowledgeBase", "getWeather"), toolNames);
    }

    @Test
    void runShouldMergeExternalToolCallbacksWhenWebSearchEnabled() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        HierarchicalMemoryAdvisor memoryAdvisor = mock(HierarchicalMemoryAdvisor.class);
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
        when(memoryAdvisor.loadMemoryContext("user-web", "session-web", "today news"))
                .thenReturn(new HierarchicalMemoryAdvisor.MemoryContext(Collections.emptyList(), "", null));

        when(externalProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{externalCallback});
        when(externalCallback.getToolDefinition()).thenReturn(externalDefinition);
        when(externalDefinition.name()).thenReturn("webSearch");
        when(externalDefinition.description()).thenReturn("联网搜索");
        when(externalDefinition.inputSchema()).thenReturn("{\"type\":\"object\"}");

        ReActAgent agent = new ReActAgent(
                builder,
                builtInTools,
                memoryAdvisor,
                new PromptSecurityFilter(),
                null,
                List.of(externalProvider)
        );

        String result = String.join("", agent.runStream("user-web", "session-web", null, "today news", true)
                .collectList()
                .block());

        assertEquals("latest answer", result);
        assertTrue(registeredCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .anyMatch("webSearch"::equals));
    }

    @Test
    void runShouldPreloadShortTermAndLongTermMemoryBeforeReasoning() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient finalAnswerChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        HierarchicalMemoryAdvisor memoryAdvisor = mock(HierarchicalMemoryAdvisor.class);
        List<List<Message>> historySnapshots = new ArrayList<>();

        Message shortTermMessage = new UserMessage("Previous preference: use concise answers.");
        Message longTermMessage = new org.springframework.ai.chat.messages.SystemMessage("LONG_TERM_MEMORY: user prefers Java.");

        when(reactChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenAnswer(invocation -> {
            List<Message> history = invocation.getArgument(0);
            historySnapshots.add(List.copyOf(history));
            return requestSpec;
        });
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("Done"));
        when(memoryAdvisor.loadMemoryContext("user-3", "session-3", "Help me now"))
                .thenReturn(new HierarchicalMemoryAdvisor.MemoryContext(
                        List.of(shortTermMessage),
                        "user prefers Java.",
                        longTermMessage
                ));

        ReActAgent agent = new ReActAgent(reactChatClient, finalAnswerChatClient, builtInTools, memoryAdvisor);

        String result = agent.run("user-3", "session-3", "Help me now");

        assertEquals("Done", result);
        assertEquals(1, historySnapshots.size());
        assertEquals(3, historySnapshots.get(0).size());
        assertEquals(longTermMessage, historySnapshots.get(0).get(0));
        assertEquals(shortTermMessage, historySnapshots.get(0).get(1));
        assertSecuredUserMessage(historySnapshots.get(0).get(2), "Help me now");
    }

    @Test
    void runStreamShouldApplySelectedModelToReasoningCall() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient finalAnswerChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec reactRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        HierarchicalMemoryAdvisor memoryAdvisor = mock(HierarchicalMemoryAdvisor.class);
        ChatModelRegistry chatModelRegistry = mock(ChatModelRegistry.class);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("deepseek-v3.2").build();

        when(reactChatClient.prompt()).thenReturn(reactRequestSpec);
        when(reactRequestSpec.options(options)).thenReturn(reactRequestSpec);
        when(reactRequestSpec.system(anyString())).thenReturn(reactRequestSpec);
        when(reactRequestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(reactRequestSpec);
        when(reactRequestSpec.messages(anyList())).thenReturn(reactRequestSpec);
        when(reactRequestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("I am ", "DeepSeek."));

        when(memoryAdvisor.loadMemoryContext("user-4", "session-4", "Who are you?"))
                .thenReturn(new HierarchicalMemoryAdvisor.MemoryContext(Collections.emptyList(), "", null));
        when(chatModelRegistry.createOptions("deepseek")).thenReturn(options);

        ReActAgent agent = new ReActAgent(
                reactChatClient,
                finalAnswerChatClient,
                builtInTools,
                memoryAdvisor,
                new PromptSecurityFilter(),
                chatModelRegistry
        );

        String result = String.join("", agent.runStream("user-4", "session-4", "deepseek", "Who are you?")
                .collectList()
                .block());

        assertEquals("I am DeepSeek.", result);
        verify(chatModelRegistry).createOptions("deepseek");
        verify(reactRequestSpec).options(options);
    }

    @Test
    void runStreamShouldRestoreMaskedValuesAcrossChunkBoundaries() {
        ChatClient reactChatClient = mock(ChatClient.class);
        ChatClient finalAnswerChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec reactRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        HierarchicalMemoryAdvisor memoryAdvisor = mock(HierarchicalMemoryAdvisor.class);

        when(reactChatClient.prompt()).thenReturn(reactRequestSpec);
        when(reactRequestSpec.system(anyString())).thenReturn(reactRequestSpec);
        when(reactRequestSpec.toolCallbacks(org.mockito.ArgumentMatchers.<List<ToolCallback>>any())).thenReturn(reactRequestSpec);
        when(reactRequestSpec.messages(anyList())).thenReturn(reactRequestSpec);
        when(reactRequestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("请联系[[PH", "ONE_1]]处理"));
        when(memoryAdvisor.loadMemoryContext("user-5", "session-5", "我的电话是 [[PHONE_1]]"))
                .thenReturn(new HierarchicalMemoryAdvisor.MemoryContext(Collections.emptyList(), "", null));

        ReActAgent agent = new ReActAgent(reactChatClient, finalAnswerChatClient, builtInTools, memoryAdvisor);

        String result = String.join("", agent.runStream("user-5", "session-5", null, "我的电话是 13800138000")
                .collectList()
                .block());

        assertEquals("请联系13800138000处理", result);
        verify(memoryAdvisor).rememberFinalTurn(
                "user-5",
                "session-5",
                secure("我的电话是 13800138000").modelInput(),
                "请联系[[PHONE_1]]处理"
        );
    }

    private void assertSecuredUserMessage(Message message, String expectedUserText) {
        String text = assertInstanceOf(UserMessage.class, message).getText();
        assertEquals(secure(expectedUserText).modelInput(), text);
    }

    private PromptSecurityFilter.SecuredPrompt secure(String userText) {
        return new PromptSecurityFilter().secure(userText);
    }
}
