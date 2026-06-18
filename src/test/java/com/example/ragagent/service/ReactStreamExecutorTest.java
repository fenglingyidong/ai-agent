package com.example.ragagent.service;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.conversation.ConversationTurnRecord;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.testsupport.RecordingChatModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReactStreamExecutorTest {

    @Test
    void executeShouldRestoreSensitiveValuesCaptureTracingAndCompleteTurn() {
        RecordingChatModel chatModel = RecordingChatModel.responding("联系 [[EMAIL_1]]");
        ConversationLogService conversationLogService = mock(ConversationLogService.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        RecordingTracing tracing = new RecordingTracing();
        ReactPromptBuilder promptBuilder = mock(ReactPromptBuilder.class);
        ChatClientRequest request = request("系统", "用户");
        whenReactInput(promptBuilder, request, "trace input");
        PromptSecurityFilter.SecuredPrompt securedPrompt = new PromptSecurityFilter()
                .secure("my email is alice@example.com");
        ReactStreamExecutor executor = new ReactStreamExecutor(
                chatModel,
                null,
                memoryAdvisor(),
                conversationMemoryService,
                conversationLogService,
                tracing,
                promptBuilder
        );

        String result = collect(executor.execute(new ReactStreamExecutor.ReactStreamRequest(
                "user-1",
                "session-1",
                null,
                request,
                List.of("searchProductKnowledge"),
                securedPrompt,
                turn(),
                tracing.rootSpan()
        )));

        assertEquals("联系 alice@example.com", result);
        assertEquals("trace input", tracing.text("llm.react.input"));
        assertEquals("联系 alice@example.com", tracing.text("llm.react.output"));
        assertSame(tracing.rootSpan(), tracing.parentWhenStarted("llm.react"));
        verify(conversationLogService).completeTurn(any(), org.mockito.ArgumentMatchers.eq("联系 alice@example.com"));
    }

    @Test
    void executeShouldRememberFallbackWhenModelStreamFails() {
        RecordingChatModel chatModel = new RecordingChatModel()
                .thenStream(Flux.error(new RuntimeException("Connection reset")));
        ConversationLogService conversationLogService = mock(ConversationLogService.class);
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        RecordingTracing tracing = new RecordingTracing();
        ReactPromptBuilder promptBuilder = mock(ReactPromptBuilder.class);
        ChatClientRequest request = request("系统", "用户");
        whenReactInput(promptBuilder, request, "trace input");
        PromptSecurityFilter.SecuredPrompt securedPrompt = new PromptSecurityFilter().secure("price?");
        ReactStreamExecutor executor = new ReactStreamExecutor(
                chatModel,
                null,
                null,
                conversationMemoryService,
                conversationLogService,
                tracing,
                promptBuilder
        );

        String result = collect(executor.execute(new ReactStreamExecutor.ReactStreamRequest(
                "user-reset",
                "session-reset",
                null,
                request,
                List.of(),
                securedPrompt,
                turn(),
                tracing.rootSpan()
        )));

        assertEquals("抱歉，大模型服务连接被中断，暂时无法完成本次回答。请稍后重试。", result);
        verify(conversationMemoryService).rememberTurn(
                "user-reset",
                "session-reset",
                securedPrompt.modelInput(),
                result
        );
        verify(conversationLogService).partialTurn(any(), org.mockito.ArgumentMatchers.eq(result),
                org.mockito.ArgumentMatchers.eq("model stream fallback"));
    }

    private ChatClientRequest request(String systemText, String userText) {
        return ChatClientRequest.builder()
                .prompt(Prompt.builder()
                        .messages(List.of(new SystemMessage(systemText), new UserMessage(userText)))
                        .build())
                .context(Map.of())
                .build();
    }

    private void whenReactInput(ReactPromptBuilder promptBuilder, ChatClientRequest request, String input) {
        org.mockito.Mockito.when(promptBuilder.reactInput(request.prompt(), List.of("searchProductKnowledge")))
                .thenReturn(input);
        org.mockito.Mockito.when(promptBuilder.reactInput(request.prompt(), List.of()))
                .thenReturn(input);
    }

    private MessageChatMemoryAdvisor memoryAdvisor() {
        return MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build()
        ).build();
    }

    private ConversationTurnRecord turn() {
        return ConversationTurnRecord.started("turn-1", "user-1", "session-1", 1L,
                "", false, "用户", 0, 1L);
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
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
    }
}
