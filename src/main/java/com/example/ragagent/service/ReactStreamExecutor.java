package com.example.ragagent.service;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.conversation.ConversationTurnRecord;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.security.PromptSecurityFilter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class ReactStreamExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReactStreamExecutor.class);

    private final ChatModel chatModel;
    private final LongTermMemoryAdvisor longTermMemoryAdvisor;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationLogService conversationLogService;
    private final RagTracing tracing;
    private final ReactPromptBuilder promptBuilder;

    ReactStreamExecutor(ChatModel chatModel,
                        LongTermMemoryAdvisor longTermMemoryAdvisor,
                        MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                        ConversationMemoryService conversationMemoryService,
                        ConversationLogService conversationLogService,
                        RagTracing tracing,
                        ReactPromptBuilder promptBuilder) {
        this.chatModel = chatModel;
        this.longTermMemoryAdvisor = longTermMemoryAdvisor;
        this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
        this.conversationMemoryService = conversationMemoryService;
        this.conversationLogService = conversationLogService;
        this.tracing = tracing == null ? new RagTracing() : tracing;
        this.promptBuilder = promptBuilder == null ? new ReactPromptBuilder(null) : promptBuilder;
    }

    Flux<String> execute(ReactStreamRequest request) {
        return Flux.defer(() -> {
            Scope rootScope = tracing.makeCurrent(request.rootSpan());
            Span span = tracing.startSpan("llm.react");
            Scope scope = tracing.makeCurrent(span);
            StreamingSensitiveValueRestorer restorer =
                    new StreamingSensitiveValueRestorer(request.securedPrompt().sensitiveValues());
            StringBuilder rawAnswerBuilder = new StringBuilder();
            StringBuilder restoredAnswerBuilder = new StringBuilder();
            AtomicBoolean fallbackUsed = new AtomicBoolean(false);
            AtomicBoolean persisted = new AtomicBoolean(false);
            try {
                ChatClientRequest advisedRequest = applyAdvisorBefore(request.baseRequest());
                tracing.capturePromptText(span, "llm.react.input",
                        promptBuilder.reactInput(advisedRequest.prompt(), request.toolNames()));

                Flux<String> restoredStream = streamModelContent(advisedRequest)
                        .<String>handle((chunk, sink) -> {
                            rawAnswerBuilder.append(chunk);
                            String restoredChunk = restorer.accept(chunk);
                            if (restoredChunk != null && !restoredChunk.isEmpty()) {
                                restoredAnswerBuilder.append(restoredChunk);
                                sink.next(restoredChunk);
                            }
                        })
                        .concatWith(Mono.fromSupplier(restorer::flush)
                                .flatMapMany(remaining -> {
                                    if (remaining == null || remaining.isEmpty()) {
                                        return Flux.empty();
                                    }
                                    restoredAnswerBuilder.append(remaining);
                                    return Flux.just(remaining);
                                }));

                return restoredStream
                        .onErrorResume(ex -> resumeWithModelStreamFallback(
                                ex,
                                request,
                                fallbackUsed,
                                rawAnswerBuilder,
                                restoredAnswerBuilder
                        ))
                        .doOnComplete(() -> {
                            String visibleAnswer = restoredAnswerBuilder.toString().trim();
                            tracing.capturePromptText(span, "llm.react.output", visibleAnswer);
                            tracing.recordTraceOutput(request.rootSpan(), visibleAnswer);
                            if (fallbackUsed.get()) {
                                conversationLogService.partialTurn(
                                        request.conversationTurn(),
                                        visibleAnswer,
                                        "model stream fallback"
                                );
                            }
                            else {
                                applyAdvisorAfter(advisedRequest, rawAnswerBuilder.toString().trim());
                                conversationLogService.completeTurn(
                                        request.conversationTurn(),
                                        visibleAnswer
                                );
                            }
                            persisted.set(true);
                            finishStreamingResponse(
                                    request,
                                    fallbackUsed.get(),
                                    rawAnswerBuilder.toString().trim()
                            );
                        })
                        .doOnError(ex -> {
                            String visibleAnswer = restoredAnswerBuilder.toString().trim();
                            tracing.capturePromptText(span, "llm.react.output", visibleAnswer);
                            tracing.recordTraceOutput(request.rootSpan(), visibleAnswer);
                            conversationLogService.failTurn(request.conversationTurn(), visibleAnswer, ex);
                            persisted.set(true);
                        })
                        .doFinally(signalType -> {
                            if (signalType == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                                String visibleAnswer = restoredAnswerBuilder.toString().trim();
                                tracing.capturePromptText(span, "llm.react.output", visibleAnswer);
                                tracing.recordTraceOutput(request.rootSpan(), visibleAnswer);
                                conversationLogService.partialTurn(
                                        request.conversationTurn(),
                                        visibleAnswer,
                                        "stream cancelled"
                                );
                            }
                            scope.close();
                            tracing.endSpan(span);
                            rootScope.close();
                        });
            }
            catch (RuntimeException ex) {
                String visibleAnswer = restoredAnswerBuilder.toString().trim();
                tracing.capturePromptText(span, "llm.react.output", visibleAnswer);
                tracing.recordTraceOutput(request.rootSpan(), visibleAnswer);
                conversationLogService.failTurn(request.conversationTurn(), visibleAnswer, ex);
                persisted.set(true);
                scope.close();
                tracing.endSpan(span);
                rootScope.close();
                return Flux.error(ex);
            }
        });
    }

    private Flux<String> resumeWithModelStreamFallback(Throwable ex,
                                                       ReactStreamRequest request,
                                                       AtomicBoolean fallbackUsed,
                                                       StringBuilder rawAnswerBuilder,
                                                       StringBuilder restoredAnswerBuilder) {
        fallbackUsed.set(true);
        log.warn(
                "ReAct stream interrupted: userId={}, sessionId={}, modelId={}, error={}",
                request.userId(),
                request.sessionId(),
                request.modelId(),
                ex == null ? "<unknown>" : ex.getMessage()
        );
        log.debug("ReAct 大模型流式调用异常堆栈", ex);
        String fallback = restoredAnswerBuilder.isEmpty()
                ? "抱歉，大模型服务连接被中断，暂时无法完成本次回答。请稍后重试。"
                : System.lineSeparator() + System.lineSeparator() + "提示：大模型服务连接被中断，以上内容可能不完整，请稍后重试。";
        rawAnswerBuilder.append(fallback);
        restoredAnswerBuilder.append(fallback);
        return Flux.just(fallback);
    }

    private ChatClientRequest applyAdvisorBefore(ChatClientRequest request) {
        ChatClientRequest current = request;
        for (BaseAdvisor advisor : memoryAdvisors()) {
            ChatClientRequest updated = advisor.before(current, NoopAdvisorChain.INSTANCE);
            if (updated != null) {
                current = updated;
            }
        }
        return current;
    }

    private void applyAdvisorAfter(ChatClientRequest request, String rawFinalAnswer) {
        if (!StringUtils.hasLength(rawFinalAnswer)) {
            return;
        }
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(
                        new Generation(new AssistantMessage(rawFinalAnswer))
                )))
                .context(request.context())
                .build();
        List<BaseAdvisor> advisors = memoryAdvisors();
        for (int i = advisors.size() - 1; i >= 0; i--) {
            ChatClientResponse updated = advisors.get(i).after(response, NoopAdvisorChain.INSTANCE);
            if (updated != null) {
                response = updated;
            }
        }
    }

    private List<BaseAdvisor> memoryAdvisors() {
        return java.util.stream.Stream.of(longTermMemoryAdvisor, messageChatMemoryAdvisor)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(BaseAdvisor::getOrder))
                .toList();
    }

    private Flux<String> streamModelContent(ChatClientRequest request) {
        if (chatModel == null) {
            return Flux.error(new IllegalStateException("ReActAgent ChatModel is not configured"));
        }
        return chatModel.stream(request.prompt())
                .map(this::responseContent)
                .filter(StringUtils::hasLength);
    }

    private String responseContent(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private void finishStreamingResponse(ReactStreamRequest request,
                                         boolean fallbackUsed,
                                         String rawFinalAnswer) {
        if (fallbackUsed) {
            conversationMemoryService.rememberTurn(
                    request.userId(),
                    request.sessionId(),
                    request.securedPrompt().modelInput(),
                    rawFinalAnswer
            );
        }

        log.info(
                "ReAct finish: userId={}, sessionId={}, modelId={}, rawAnswerLength={}",
                request.userId(),
                request.sessionId(),
                request.modelId(),
                textLength(rawFinalAnswer)
        );
    }

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    record ReactStreamRequest(String userId,
                              String sessionId,
                              String modelId,
                              ChatClientRequest baseRequest,
                              List<String> toolNames,
                              PromptSecurityFilter.SecuredPrompt securedPrompt,
                              ConversationTurnRecord conversationTurn,
                              Span rootSpan) {

        ReactStreamRequest {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }
    }

    private enum NoopAdvisorChain implements AdvisorChain {
        INSTANCE
    }
}
