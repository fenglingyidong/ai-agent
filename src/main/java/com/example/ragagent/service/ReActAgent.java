package com.example.ragagent.service;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.conversation.ConversationTurnRecord;
import com.example.ragagent.mall.MallMcpClient;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.prompt.PromptTemplateStore;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.tools.BuiltInTools;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final String TOOL_CONTEXT_USER_ID = "userId";
    private static final String TOOL_CONTEXT_SESSION_ID = "sessionId";
    private static final String REACT_SYSTEM_PROMPT = "react.system";
    private static final String REACT_MALL_RULE_ENABLED = "react.mall-rule.enabled";
    private static final String REACT_MALL_RULE_DISABLED = "react.mall-rule.disabled";
    private static final String REACT_NETWORK_RULE_ENABLED = "react.network-rule.enabled";
    private static final String REACT_NETWORK_RULE_DISABLED = "react.network-rule.disabled";
    private static final String REACT_TASK_POLICY_HEADER = "react.task-policy.header";
    private static final String REACT_TASK_POLICY_ITEM = "react.task-policy.item";
    private static final String REACT_TASK_POLICY_ALLOWED_TOOLS = "react.task-policy.allowed-tools";
    private static final String REACT_TASK_POLICY_CONFIRMATION_REQUIRED = "react.task-policy.confirmation-required";

    private final BuiltInTools builtInTools;
    private final LongTermMemoryAdvisor longTermMemoryAdvisor;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationLogService conversationLogService;
    private final PromptSecurityFilter promptSecurityFilter;
    private final ChatModelRegistry chatModelRegistry;
    private final ShoppingRouteExecutor shoppingRouteExecutor;
    private final List<ToolCallbackProvider> externalToolCallbackProviders;
    private final MallMcpToolCallback mallMcpToolCallback;
    private final ChatModel reactChatModel;
    private final List<ToolCallback> builtInToolCallbacks;
    private final RagTracing tracing;
    private final PromptTemplateStore promptTemplateStore;

    public ReActAgent(ChatClient.Builder builder,
                      BuiltInTools builtInTools,
                      LongTermMemoryAdvisor longTermMemoryAdvisor,
                      MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                      ConversationMemoryService conversationMemoryService,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry,
                      ShoppingRouteExecutor shoppingRouteExecutor,
                      List<ToolCallbackProvider> externalToolCallbackProviders,
                      MallMcpClient mallMcpClient,
                      ConversationLogService conversationLogService) {
        this(
                builder,
                chatModelFromBuilder(builder),
                builtInTools,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                promptSecurityFilter,
                chatModelRegistry,
                shoppingRouteExecutor,
                externalToolCallbackProviders,
                mallMcpClient,
                conversationLogService,
                new RagTracing(),
                new PromptTemplateStore()
        );
    }

    public ReActAgent(ChatClient.Builder builder,
                      BuiltInTools builtInTools,
                      LongTermMemoryAdvisor longTermMemoryAdvisor,
                      MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                      ConversationMemoryService conversationMemoryService,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry,
                      ShoppingRouteExecutor shoppingRouteExecutor,
                      List<ToolCallbackProvider> externalToolCallbackProviders,
                      MallMcpClient mallMcpClient,
                      ConversationLogService conversationLogService,
                      RagTracing tracing,
                      PromptTemplateStore promptTemplateStore) {
        this(
                builder,
                chatModelFromBuilder(builder),
                builtInTools,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                promptSecurityFilter,
                chatModelRegistry,
                shoppingRouteExecutor,
                externalToolCallbackProviders,
                mallMcpClient,
                conversationLogService,
                tracing,
                promptTemplateStore
        );
    }

    @Autowired
    public ReActAgent(ChatClient.Builder builder,
                      ChatModel chatModel,
                      BuiltInTools builtInTools,
                      LongTermMemoryAdvisor longTermMemoryAdvisor,
                      MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                      ConversationMemoryService conversationMemoryService,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry,
                      ShoppingRouteExecutor shoppingRouteExecutor,
                      List<ToolCallbackProvider> externalToolCallbackProviders,
                      MallMcpClient mallMcpClient,
                      ConversationLogService conversationLogService,
                      RagTracing tracing,
                      PromptTemplateStore promptTemplateStore) {
        this.builtInTools = builtInTools;
        this.longTermMemoryAdvisor = longTermMemoryAdvisor;
        this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
        this.conversationMemoryService = conversationMemoryService;
        this.conversationLogService = conversationLogService;
        this.promptSecurityFilter = promptSecurityFilter;
        this.chatModelRegistry = chatModelRegistry;
        this.shoppingRouteExecutor = shoppingRouteExecutor;
        this.externalToolCallbackProviders = externalToolCallbackProviders == null
                ? List.of()
                : List.copyOf(externalToolCallbackProviders);
        this.mallMcpToolCallback = mallMcpClient == null
                ? null
                : new MallMcpToolCallback(mallMcpClient);
        this.reactChatModel = chatModel;
        this.builtInToolCallbacks = loadBuiltInToolCallbacks();
        this.tracing = tracing == null ? new RagTracing() : tracing;
        this.promptTemplateStore = promptTemplateStore == null ? new PromptTemplateStore() : promptTemplateStore;
    }

    public ReActAgent(ChatClient.Builder builder,
                      BuiltInTools builtInTools,
                      LongTermMemoryAdvisor longTermMemoryAdvisor,
                      MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                      ConversationMemoryService conversationMemoryService,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry,
                      ShoppingRouteExecutor shoppingRouteExecutor,
                      List<ToolCallbackProvider> externalToolCallbackProviders,
                      MallMcpClient mallMcpClient,
                      ConversationLogService conversationLogService,
                      RagTracing tracing) {
        this(
                builder,
                chatModelFromBuilder(builder),
                builtInTools,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                promptSecurityFilter,
                chatModelRegistry,
                shoppingRouteExecutor,
                externalToolCallbackProviders,
                mallMcpClient,
                conversationLogService,
                tracing,
                new PromptTemplateStore()
        );
    }

    public ReActAgent(ChatClient.Builder builder,
                      BuiltInTools builtInTools,
                      LongTermMemoryAdvisor longTermMemoryAdvisor,
                      MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                      ConversationMemoryService conversationMemoryService,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry,
                      ShoppingRouteExecutor shoppingRouteExecutor,
                      List<ToolCallbackProvider> externalToolCallbackProviders,
                      ConversationLogService conversationLogService) {
        this(
                builder,
                chatModelFromBuilder(builder),
                builtInTools,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                promptSecurityFilter,
                chatModelRegistry,
                shoppingRouteExecutor,
                externalToolCallbackProviders,
                null,
                conversationLogService,
                new RagTracing(),
                new PromptTemplateStore()
        );
    }

    public Flux<String> runStream(String userId,
                                  String sessionId,
                                  String modelId,
                                  String userMessage,
                                  boolean webSearchEnabled,
                                  List<Media> media,
                                  String mallToken,
                                  String mallUsername,
                                  String mallPassword) {
        List<Media> safeMedia = media == null ? List.of() : media;
        writeRequestTraceAttributes(userId, sessionId, modelId, webSearchEnabled, safeMedia.size());
        ConversationTurnRecord conversationTurn = conversationLogService.beginTurn(
                userId,
                sessionId,
                modelId,
                webSearchEnabled,
                userMessage,
                safeMedia.size()
        );
        try {
            Span rootSpan = tracing.currentSpan();
            tracing.recordTraceInput(rootSpan, userMessage);
            PromptSecurityFilter.SecuredPrompt preRouteSecuredPrompt = promptSecurityFilter.secure(userMessage);
            RoutedAgentRequest routedRequest = routeBeforeCore(
                    userId,
                    sessionId,
                    preRouteSecuredPrompt.safeInput(),
                    safeMedia,
                    mallToken,
                    mallUsername,
                    mallPassword
            );
            tracing.setAttribute(tracing.currentSpan(), "app.route.short_circuit", routedRequest.shortCircuitStream() != null);
            tracing.setAttribute(tracing.currentSpan(), "app.route.mall_tools_allowed", routedRequest.mallToolsAllowed());
            if (routedRequest.shortCircuitStream() != null) {
                return rememberShortCircuitTurn(
                        userId,
                        sessionId,
                        routedRequest.userMessage(),
                        routedRequest.shortCircuitStream(),
                        preRouteSecuredPrompt,
                        conversationTurn,
                        rootSpan
                );
            }

            PromptSecurityFilter.SecuredPrompt securedPrompt = promptSecurityFilter.secure(
                    routedRequest.userMessage(),
                    preRouteSecuredPrompt.sensitiveValues()
            );
            return runCoreStream(
                    userId,
                    sessionId,
                    modelId,
                    webSearchEnabled,
                    routedRequest.media(),
                    routedRequest.mallToolsAllowed(),
                    routedRequest.taskPolicies(),
                    routedRequest.orderCreationAllowed(),
                    routedRequest.trustedContext(),
                    securedPrompt,
                    conversationTurn,
                    rootSpan
            );
        }
        catch (RuntimeException ex) {
            conversationLogService.failTurn(conversationTurn, "", ex);
            throw ex;
        }
    }

    private void writeRequestTraceAttributes(String userId,
                                             String sessionId,
                                             String modelId,
                                             boolean webSearchEnabled,
                                             int mediaCount) {
        Span span = tracing.currentSpan();
        tracing.setAttribute(span, "langfuse.trace.name", "POST /api/react");
        tracing.setAttribute(span, "langfuse.user.id", StringUtils.hasText(userId) ? userId : "anonymous");
        tracing.setAttribute(span, "langfuse.session.id", StringUtils.hasText(sessionId) ? sessionId : "default");
        tracing.setAttribute(span, "app.user_id", StringUtils.hasText(userId) ? userId : "anonymous");
        tracing.setAttribute(span, "app.session_id", StringUtils.hasText(sessionId) ? sessionId : "default");
        tracing.setAttribute(span, "app.model_id", resolvedTraceModelId(modelId));
        tracing.setAttribute(span, "app.web_search_enabled", webSearchEnabled);
        tracing.setAttribute(span, "app.media_count", mediaCount);
    }

    private String resolvedTraceModelId(String modelId) {
        String resolvedModelId = chatModelRegistry == null ? null : chatModelRegistry.resolveModelId(modelId);
        return StringUtils.hasText(resolvedModelId) ? resolvedModelId : "default";
    }

    private Flux<String> rememberShortCircuitTurn(String userId,
                                                  String sessionId,
                                                  String userMessage,
                                                  Flux<String> shortCircuitStream,
                                                  PromptSecurityFilter.SecuredPrompt preRouteSecuredPrompt,
                                                  ConversationTurnRecord conversationTurn,
                                                  Span rootSpan) {
        PromptSecurityFilter.SecuredPrompt securedPrompt = promptSecurityFilter.secure(
                userMessage,
                preRouteSecuredPrompt.sensitiveValues()
        );
        return Flux.defer(() -> {
            StreamingSensitiveValueRestorer restorer =
                    new StreamingSensitiveValueRestorer(preRouteSecuredPrompt.sensitiveValues());
            StringBuilder memoryAnswerBuilder = new StringBuilder();
            StringBuilder visibleAnswerBuilder = new StringBuilder();
            AtomicBoolean persisted = new AtomicBoolean(false);
            Flux<String> restoredStream = shortCircuitStream
                    .<String>handle((chunk, sink) -> {
                        memoryAnswerBuilder.append(chunk);
                        String restoredChunk = restorer.accept(chunk);
                        if (restoredChunk != null && !restoredChunk.isEmpty()) {
                            visibleAnswerBuilder.append(restoredChunk);
                            sink.next(restoredChunk);
                        }
                    })
                    .concatWith(Mono.fromSupplier(restorer::flush)
                            .flatMapMany(remaining -> {
                                if (remaining == null || remaining.isEmpty()) {
                                    return Flux.empty();
                                }
                                visibleAnswerBuilder.append(remaining);
                                return Flux.just(remaining);
                            }));
            return restoredStream
                    .doOnComplete(() -> {
                        conversationMemoryService.rememberTurn(
                                userId,
                                sessionId,
                                securedPrompt.modelInput(),
                                memoryAnswerBuilder.toString().trim()
                        );
                        String visibleAnswer = visibleAnswerBuilder.toString().trim();
                        tracing.recordTraceOutput(rootSpan, visibleAnswer);
                        conversationLogService.completeTurn(conversationTurn, visibleAnswer);
                        persisted.set(true);
                    })
                    .doOnError(ex -> {
                        String visibleAnswer = visibleAnswerBuilder.toString().trim();
                        tracing.recordTraceOutput(rootSpan, visibleAnswer);
                        conversationLogService.failTurn(conversationTurn, visibleAnswer, ex);
                        persisted.set(true);
                    })
                    .doFinally(signalType -> {
                        if (signalType == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                            String visibleAnswer = visibleAnswerBuilder.toString().trim();
                            tracing.recordTraceOutput(rootSpan, visibleAnswer);
                            conversationLogService.partialTurn(
                                    conversationTurn,
                                    visibleAnswer,
                                    "stream cancelled"
                            );
                        }
                    });
        });
    }

    private Flux<String> runCoreStream(String userId,
                                       String sessionId,
                                         String modelId,
                                         boolean webSearchEnabled,
                                         List<Media> media,
                                         boolean mallToolsAllowed,
                                         List<ShoppingTaskPolicy> taskPolicies,
                                         boolean orderCreationAllowed,
                                         String trustedContext,
                                         PromptSecurityFilter.SecuredPrompt securedPrompt,
                                         ConversationTurnRecord conversationTurn,
                                         Span rootSpan) {
        ActiveToolCallbacks activeTools;
        try {
            activeTools = resolveActiveToolCallbacks(
                    userId,
                    sessionId,
                    webSearchEnabled,
                    mallToolsAllowed,
                    taskPolicies,
                    orderCreationAllowed
            );
        }
        catch (MallMcpToolResolutionException ex) {
            return rememberToolResolutionFailure(userId, sessionId, securedPrompt, conversationTurn, ex);
        }
        logAgentStart(userId, sessionId, modelId, webSearchEnabled, mediaCount(media), activeTools.callbacks().size(), securedPrompt);

        Message currentUserMessage = buildCurrentUserMessage(securedPrompt.modelInput(), media);
        log.debug(
                "ReAct memory: userId={}, sessionId={}, webSearchEnabled={}, mediaCount={}, toolCount={}, conversationId={}",
                userId,
                sessionId,
                webSearchEnabled,
                mediaCount(media),
                activeTools.callbacks().size(),
                conversationMemoryService.buildConversationId(userId, sessionId)
        );

        return streamLlmResponse(
                userId,
                sessionId,
                modelId,
                currentUserMessage,
                activeTools,
                webSearchEnabled,
                taskPolicies,
                trustedContext,
                securedPrompt,
                conversationTurn,
                rootSpan
        );
    }

    private Flux<String> rememberToolResolutionFailure(String userId,
                                                       String sessionId,
                                                       PromptSecurityFilter.SecuredPrompt securedPrompt,
                                                       ConversationTurnRecord conversationTurn,
                                                       MallMcpToolResolutionException ex) {
        String answer = mallMcpFailureMessage(ex);
        return Flux.just(answer)
                .doOnComplete(() -> {
                    conversationMemoryService.rememberTurn(
                            userId,
                            sessionId,
                            securedPrompt.modelInput(),
                            answer
                    );
                    conversationLogService.completeTurn(conversationTurn, answer);
                });
    }

    private RoutedAgentRequest routeBeforeCore(String userId,
                                               String sessionId,
                                               String userMessage,
                                               List<Media> media,
                                               String mallToken,
                                               String mallUsername,
                                               String mallPassword) {
        if (shoppingRouteExecutor != null) {
            return shoppingRouteExecutor.routeBeforeCore(userId, sessionId, userMessage, media, mallToken, mallUsername, mallPassword);
        }
        List<Media> safeMedia = media == null ? List.of() : media;
        return new RoutedAgentRequest(appendMultimodalInstruction(userMessage, safeMedia.size()), safeMedia, null);
    }

    private String appendMultimodalInstruction(String message, int mediaCount) {
        String normalizedMessage = StringUtils.hasText(message) ? message.trim() : "请基于图片帮我推荐相似商品";
        if (mediaCount <= 0) {
            return normalizedMessage;
        }
        return normalizedMessage + System.lineSeparator()
                + "用户同时上传了 " + mediaCount + " 张商品图片。请先理解图片中的品类、颜色、风格、材质和显著规格，再结合文本需求调用商品检索、相似款、价格库存或购物车工具。";
    }

    private UserMessage buildCurrentUserMessage(String modelUserMessage, List<Media> media) {
        if (media == null || media.isEmpty()) {
            return new UserMessage(modelUserMessage);
        }
        return UserMessage.builder()
                .text(modelUserMessage)
                .media(media)
                .build();
    }

    private Flux<String> streamLlmResponse(String userId,
                                           String sessionId,
                                           String modelId,
                                           Message currentUserMessage,
                                           ActiveToolCallbacks activeTools,
                                           boolean webSearchEnabled,
                                           List<ShoppingTaskPolicy> taskPolicies,
                                           String trustedContext,
                                           PromptSecurityFilter.SecuredPrompt securedPrompt,
                                           ConversationTurnRecord conversationTurn,
                                           Span rootSpan) {
        String reactSystemPrompt = withTrustedSystemContext(buildReactSystemPrompt(
                activeTools.hasExternalTools(),
                activeTools.hasMallTools(),
                webSearchEnabled,
                taskPolicies,
                activeTools.toolNameSet()
        ), trustedContext);
        String conversationId = conversationMemoryService.buildConversationId(userId, sessionId);
        Map<String, Object> toolContext = buildToolContext(userId, sessionId);
        ChatClientRequest baseRequest = buildChatClientRequest(
                modelId,
                reactSystemPrompt,
                currentUserMessage,
                activeTools,
                toolContext,
                buildAdvisorContext(userId, sessionId, conversationId)
        );

        return Flux.defer(() -> {
            Scope rootScope = tracing.makeCurrent(rootSpan);
            Span span = tracing.startSpan("llm.react");
            Scope scope = tracing.makeCurrent(span);
            StreamingSensitiveValueRestorer restorer =
                    new StreamingSensitiveValueRestorer(securedPrompt.sensitiveValues());
            StringBuilder rawAnswerBuilder = new StringBuilder();
            StringBuilder restoredAnswerBuilder = new StringBuilder();
            AtomicBoolean fallbackUsed = new AtomicBoolean(false);
            AtomicBoolean persisted = new AtomicBoolean(false);
            try {
                ChatClientRequest advisedRequest = applyAdvisorBefore(baseRequest);
                tracing.capturePromptText(span, "llm.react.input", reactInput(advisedRequest.prompt(), activeTools));
                printFinalPrompt(modelId, advisedRequest, activeTools);

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
                                userId,
                                sessionId,
                                modelId,
                                fallbackUsed,
                                rawAnswerBuilder,
                                restoredAnswerBuilder
                        ))
                        .doOnComplete(() -> {
                            String visibleAnswer = restoredAnswerBuilder.toString().trim();
                            tracing.capturePromptText(span, "llm.react.output", visibleAnswer);
                            tracing.recordTraceOutput(rootSpan, visibleAnswer);
                            if (fallbackUsed.get()) {
                                conversationLogService.partialTurn(
                                        conversationTurn,
                                        visibleAnswer,
                                        "model stream fallback"
                                );
                            }
                            else {
                                applyAdvisorAfter(advisedRequest, rawAnswerBuilder.toString().trim());
                                conversationLogService.completeTurn(
                                        conversationTurn,
                                        visibleAnswer
                                );
                            }
                            persisted.set(true);
                            finishStreamingResponse(
                                    userId,
                                    sessionId,
                                    modelId,
                                    securedPrompt,
                                    fallbackUsed.get(),
                                    rawAnswerBuilder.toString().trim()
                            );
                        })
                        .doOnError(ex -> {
                            String visibleAnswer = restoredAnswerBuilder.toString().trim();
                            tracing.capturePromptText(span, "llm.react.output", visibleAnswer);
                            tracing.recordTraceOutput(rootSpan, visibleAnswer);
                            conversationLogService.failTurn(conversationTurn, visibleAnswer, ex);
                            persisted.set(true);
                        })
                        .doFinally(signalType -> {
                            if (signalType == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                                String visibleAnswer = restoredAnswerBuilder.toString().trim();
                                tracing.capturePromptText(span, "llm.react.output", visibleAnswer);
                                tracing.recordTraceOutput(rootSpan, visibleAnswer);
                                conversationLogService.partialTurn(
                                        conversationTurn,
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
                tracing.recordTraceOutput(rootSpan, visibleAnswer);
                conversationLogService.failTurn(conversationTurn, visibleAnswer, ex);
                persisted.set(true);
                scope.close();
                tracing.endSpan(span);
                rootScope.close();
                return Flux.error(ex);
            }
        });
    }

    private String withTrustedSystemContext(String systemPrompt, String trustedContext) {
        if (!StringUtils.hasText(trustedContext)) {
            return systemPrompt;
        }
        return systemPrompt
                + System.lineSeparator()
                + System.lineSeparator()
                + trustedContext.trim();
    }

    private Flux<String> resumeWithModelStreamFallback(Throwable ex,
                                                       String userId,
                                                       String sessionId,
                                                       String modelId,
                                                       AtomicBoolean fallbackUsed,
                                                       StringBuilder rawAnswerBuilder,
                                                       StringBuilder restoredAnswerBuilder) {
        fallbackUsed.set(true);
        log.warn(
                "ReAct stream interrupted: userId={}, sessionId={}, modelId={}, error={}",
                userId,
                sessionId,
                modelId,
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

    private ChatClientRequest buildChatClientRequest(String modelId,
                                                     String systemPrompt,
                                                     Message currentUserMessage,
                                                     ActiveToolCallbacks activeTools,
                                                     Map<String, Object> toolContext,
                                                     Map<String, Object> advisorContext) {
        Prompt prompt = Prompt.builder()
                .messages(List.of(new SystemMessage(systemPrompt), currentUserMessage))
                .chatOptions(buildModelOptions(modelId, activeTools, toolContext))
                .build();
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(advisorContext)
                .build();
    }

    private OpenAiChatOptions buildModelOptions(String modelId,
                                                ActiveToolCallbacks activeTools,
                                                Map<String, Object> toolContext) {
        OpenAiChatOptions selectedOptions = chatModelRegistry == null
                ? null
                : chatModelRegistry.createOptions(modelId);
        OpenAiChatOptions options = selectedOptions == null ? OpenAiChatOptions.builder().build() : selectedOptions;
        options.setToolCallbacks(activeTools.callbacks());
        options.setToolContext(toolContext);
        options.setInternalToolExecutionEnabled(true);
        return options;
    }

    private Map<String, Object> buildAdvisorContext(String userId, String sessionId, String conversationId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(ChatMemory.CONVERSATION_ID, conversationId);
        context.put(LongTermMemoryAdvisor.USER_ID_KEY, userId);
        context.put(LongTermMemoryAdvisor.SESSION_ID_KEY, sessionId);
        return context;
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
        if (reactChatModel == null) {
            return Flux.error(new IllegalStateException("ReActAgent ChatModel is not configured"));
        }
        return reactChatModel.stream(request.prompt())
                .map(this::responseContent)
                .filter(StringUtils::hasLength);
    }

    private void printFinalPrompt(String requestedModelId,
                                  ChatClientRequest request,
                                  ActiveToolCallbacks activeTools) {
        Prompt prompt = request.prompt();
        System.out.println();
        System.out.println("========== ReActAgent FINAL PROMPT ==========");
        System.out.println("requestedModelId: " + displayDebugValue(requestedModelId, "<default>"));
        System.out.println("resolvedModelId: " + displayDebugValue(resolveModelIdForDebug(requestedModelId), "<none>"));
        System.out.println("resolvedModelName: " + displayDebugValue(resolveModelNameForDebug(requestedModelId, prompt), "<provider-default>"));
        System.out.println("toolNames: " + activeTools.toolNames());
        System.out.println();
        System.out.println(reactInput(prompt, activeTools));
        System.out.println("======== END ReActAgent FINAL PROMPT ========");
        System.out.println();
    }

    private String resolveModelIdForDebug(String requestedModelId) {
        return chatModelRegistry == null ? "" : chatModelRegistry.resolveModelId(requestedModelId);
    }

    private String resolveModelNameForDebug(String requestedModelId, Prompt prompt) {
        if (chatModelRegistry != null) {
            String modelName = chatModelRegistry.resolveModelName(requestedModelId);
            if (StringUtils.hasText(modelName)) {
                return modelName;
            }
        }
        ChatOptions options = prompt == null ? null : prompt.getOptions();
        if (options instanceof OpenAiChatOptions openAiOptions) {
            return openAiOptions.getModel();
        }
        return "";
    }

    private String displayDebugValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String responseContent(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private Map<String, Object> buildToolContext(String userId, String sessionId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(TOOL_CONTEXT_USER_ID, StringUtils.hasText(userId) ? userId.trim() : "");
        context.put(TOOL_CONTEXT_SESSION_ID, StringUtils.hasText(sessionId) ? sessionId.trim() : "");
        context.put(BuiltInTools.TOOL_CONTEXT_SEARCH_PRODUCT_KNOWLEDGE_CACHE, new ConcurrentHashMap<String, String>());
        return context;
    }

    private void logAgentStart(String userId,
                               String sessionId,
                               String modelId,
                               boolean webSearchEnabled,
                               int mediaCount,
                               int toolCount,
                               PromptSecurityFilter.SecuredPrompt securedPrompt) {
        log.info(
                "ReAct start: userId={}, sessionId={}, modelId={}, webSearchEnabled={}, mediaCount={}, toolCount={}, maskedValues={}",
                userId,
                sessionId,
                modelId,
                webSearchEnabled,
                mediaCount,
                toolCount,
                securedPrompt.sensitiveValues().size()
        );
        log.debug(
                "ReAct prompt lengths: userId={}, sessionId={}, original={}, safe={}, model={}",
                userId,
                sessionId,
                textLength(securedPrompt.originalInput()),
                textLength(securedPrompt.safeInput()),
                textLength(securedPrompt.modelInput())
        );
    }

    private void finishStreamingResponse(String userId,
                                         String sessionId,
                                         String modelId,
                                         PromptSecurityFilter.SecuredPrompt securedPrompt,
                                         boolean fallbackUsed,
                                         String rawFinalAnswer) {
        if (fallbackUsed) {
            conversationMemoryService.rememberTurn(
                    userId,
                    sessionId,
                    securedPrompt.modelInput(),
                    rawFinalAnswer
            );
        }

        log.info(
                "ReAct finish: userId={}, sessionId={}, modelId={}, rawAnswerLength={}",
                userId,
                sessionId,
                modelId,
                textLength(rawFinalAnswer)
        );
    }

    private int mediaCount(List<Media> media) {
        return media == null ? 0 : media.size();
    }

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private List<ToolCallback> loadBuiltInToolCallbacks() {
        if (builtInTools == null) {
            return List.of();
        }
        return List.of(ToolCallbacks.from(builtInTools));
    }

    private ActiveToolCallbacks resolveActiveToolCallbacks(String userId,
                                                           String sessionId,
                                                           boolean webSearchEnabled,
                                                           boolean mallToolsAllowed,
                                                           List<ShoppingTaskPolicy> taskPolicies,
                                                           boolean orderCreationAllowed) {
        Map<String, ToolCallback> activeToolCallbacks = new LinkedHashMap<>();
        builtInToolCallbacks.forEach(callback -> putToolCallback(
                activeToolCallbacks,
                callback,
                userId,
                sessionId,
                orderCreationAllowed
        ));

        if (allowsMallTools(mallToolsAllowed, taskPolicies) && mallMcpToolCallback != null) {
            try {
                List<ToolCallback> mallToolCallbacks = mallMcpToolCallback.getToolCallbacks();
                if (mallToolCallbacks.isEmpty()) {
                    throw new MallMcpToolResolutionException("未发现 mall_* MCP 工具");
                }
                for (ToolCallback callback : mallToolCallbacks) {
                    putToolCallback(activeToolCallbacks, callback, userId, sessionId, orderCreationAllowed);
                }
            }
            catch (RuntimeException ex) {
                throw new MallMcpToolResolutionException(safeMallMcpMessage(ex), ex);
            }
        }

        if (!webSearchEnabled) {
            List<ToolCallback> callbacks = filterCallbacksByTaskPolicies(
                    List.copyOf(activeToolCallbacks.values()),
                    taskPolicies,
                    orderCreationAllowed
            );
            return new ActiveToolCallbacks(callbacks, false, hasMallTools(callbacks));
        }

        boolean hasExternalTools = false;
        for (ToolCallbackProvider provider : externalToolCallbackProviders) {
            try {
                ToolCallback[] callbacks = provider.getToolCallbacks();
                if (callbacks == null) {
                    continue;
                }

                for (ToolCallback callback : callbacks) {
                    String toolName = toolName(callback);
                    if (!StringUtils.hasText(toolName) || activeToolCallbacks.containsKey(toolName)) {
                        continue;
                    }
                    if (MallMcpToolCallback.isMallTool(toolName)) {
                        continue;
                    }
                    putToolCallback(activeToolCallbacks, callback, userId, sessionId, orderCreationAllowed);
                    hasExternalTools = true;
                }
            }
            catch (RuntimeException ex) {
                log.warn("Failed to resolve MCP tool callbacks, MCP tools will be skipped for this request", ex);
            }
        }

        List<ToolCallback> callbacks = filterCallbacksByTaskPolicies(
                List.copyOf(activeToolCallbacks.values()),
                taskPolicies,
                orderCreationAllowed
        );
        return new ActiveToolCallbacks(callbacks, hasExternalTools, hasMallTools(callbacks));
    }

    private List<ToolCallback> filterCallbacksByTaskPolicies(List<ToolCallback> callbacks,
                                                             List<ShoppingTaskPolicy> taskPolicies,
                                                             boolean orderCreationAllowed) {
        if (callbacks == null || callbacks.isEmpty()) {
            return List.of();
        }

        List<ShoppingTaskPolicy> safeTaskPolicies = taskPolicies == null ? List.of() : taskPolicies;
        java.util.Set<String> allowedToolNames = safeTaskPolicies.stream()
                .flatMap(policy -> policy.allowedToolNames().stream())
                .collect(java.util.stream.Collectors.toSet());

        return callbacks.stream()
                .filter(callback -> {
                    String name = toolName(callback);
                    if (MallTool.CREATE_ORDER.toolName().equals(name) && !orderCreationAllowed) {
                        return false;
                    }
                    return safeTaskPolicies.isEmpty()
                            || allowedToolNames.isEmpty()
                            || !isShoppingControlledTool(name)
                            || allowedToolNames.contains(name);
                })
                .toList();
    }

    private boolean allowsMallTools(boolean mallToolsAllowed, List<ShoppingTaskPolicy> taskPolicies) {
        if (!mallToolsAllowed) {
            return false;
        }
        java.util.Set<String> allowedToolNames = allowedToolNames(taskPolicies);
        return allowedToolNames.isEmpty() || allowedToolNames.stream().anyMatch(MallMcpToolCallback::isMallTool);
    }

    private boolean isShoppingControlledTool(String toolName) {
        return "searchProductKnowledge".equals(toolName)
                || MallMcpToolCallback.isMallTool(toolName);
    }

    private boolean hasMallTools(List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return false;
        }
        return callbacks.stream()
                .map(this::toolName)
                .anyMatch(MallMcpToolCallback::isMallTool);
    }

    private java.util.Set<String> allowedToolNames(List<ShoppingTaskPolicy> taskPolicies) {
        if (taskPolicies == null || taskPolicies.isEmpty()) {
            return java.util.Set.of();
        }
        return taskPolicies.stream()
                .flatMap(policy -> policy.allowedToolNames().stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    private void putToolCallback(Map<String, ToolCallback> activeToolCallbacks,
                                 ToolCallback callback,
                                 String userId,
                                 String sessionId,
                                 boolean orderCreationAllowed) {
        String toolName = toolName(callback);
        if (StringUtils.hasText(toolName)) {
            ToolCallback guardedCallback = MallTool.CREATE_ORDER.toolName().equals(toolName)
                    ? new OrderCreationGuardedToolCallback(callback, orderCreationAllowed)
                    : callback;
            activeToolCallbacks.put(toolName, new LoggingToolCallback(guardedCallback, userId, sessionId, tracing));
        }
    }

    private String toolName(ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null) {
            return "";
        }
        return callback.getToolDefinition().name();
    }

    private String buildReactSystemPrompt(boolean hasExternalTools,
                                          boolean hasMallTools,
                                          boolean webSearchEnabled,
                                          List<ShoppingTaskPolicy> taskPolicies,
                                          Set<String> activeToolNames) {
        String mallRule = hasMallTools
                ? promptTemplateStore.text(REACT_MALL_RULE_ENABLED)
                : promptTemplateStore.text(REACT_MALL_RULE_DISABLED);
        String networkRule = webSearchEnabled && hasExternalTools
                ? promptTemplateStore.text(REACT_NETWORK_RULE_ENABLED)
                : promptTemplateStore.text(REACT_NETWORK_RULE_DISABLED);
        String policyPrompt = renderTaskPolicyPrompt(taskPolicies, activeToolNames);
        return promptTemplateStore.render(REACT_SYSTEM_PROMPT, Map.of(
                "mall_rule", mallRule,
                "network_rule", networkRule,
                "task_policy_prompt", StringUtils.hasText(policyPrompt)
                        ? System.lineSeparator() + System.lineSeparator() + policyPrompt
                        : ""
        ));
    }

    private String reactInput(Prompt prompt, ActiveToolCallbacks activeTools) {
        StringBuilder builder = new StringBuilder();
        List<Message> messages = prompt == null ? List.of() : prompt.getInstructions();
        for (Message message : messages) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append(messageRole(message)).append(":").append(System.lineSeparator())
                    .append(messageText(message));
            if (message instanceof UserMessage) {
                builder.append(System.lineSeparator())
                        .append("media_count: ")
                        .append(mediaCountFromMessage(message));
            }
        }
        builder.append(System.lineSeparator())
                .append("tools: ")
                .append(activeTools.toolNames());
        return builder.toString();
    }

    private String messageRole(Message message) {
        if (message == null || message.getMessageType() == null) {
            return "message";
        }
        return message.getMessageType().name().toLowerCase(java.util.Locale.ROOT);
    }

    private String messageText(Message message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.getText();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.getText();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return message == null ? "" : String.valueOf(message);
    }

    private int mediaCountFromMessage(Message message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.getMedia().size();
        }
        return 0;
    }

    private String renderTaskPolicyPrompt(List<ShoppingTaskPolicy> taskPolicies, Set<String> activeToolNames) {
        if (taskPolicies == null || taskPolicies.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(promptTemplateStore.text(REACT_TASK_POLICY_HEADER));
        boolean hasRenderablePolicy = false;
        for (ShoppingTaskPolicy policy : taskPolicies) {
            if (!isRenderableTaskPolicy(policy)) {
                continue;
            }
            builder.append(System.lineSeparator())
                    .append(renderTaskPolicyItem(policy, activeToolNames));
            hasRenderablePolicy = true;
        }
        return hasRenderablePolicy ? builder.toString() : "";
    }

    private boolean isRenderableTaskPolicy(ShoppingTaskPolicy policy) {
        return policy != null
                && (StringUtils.hasText(policy.promptFragment())
                || !policy.allowedToolNames().isEmpty()
                || policy.confirmationRequired());
    }

    private String renderTaskPolicyItem(ShoppingTaskPolicy policy, Set<String> activeToolNames) {
        return promptTemplateStore.render(REACT_TASK_POLICY_ITEM, Map.of(
                "policy_id", policy.id(),
                "policy_name", policy.name(),
                "policy_prompt", policy.promptFragment(),
                "allowed_tools_text", renderAllowedToolsText(policy, activeToolNames),
                "confirmation_required_text", renderConfirmationRequiredText(policy)
        ));
    }

    private String renderAllowedToolsText(ShoppingTaskPolicy policy, Set<String> activeToolNames) {
        if (policy.allowedToolNames().isEmpty()) {
            return "";
        }
        Set<String> renderedToolNames = visibleAllowedToolNames(policy, activeToolNames);
        if (renderedToolNames.isEmpty()) {
            return "";
        }
        return promptTemplateStore.render(REACT_TASK_POLICY_ALLOWED_TOOLS, Map.of(
                "allowed_tools", String.join(", ", renderedToolNames)
        ));
    }

    private Set<String> visibleAllowedToolNames(ShoppingTaskPolicy policy, Set<String> activeToolNames) {
        if (activeToolNames == null) {
            return policy.allowedToolNames();
        }
        if (activeToolNames.isEmpty()) {
            return Set.of();
        }
        return policy.allowedToolNames().stream()
                .filter(activeToolNames::contains)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private String renderConfirmationRequiredText(ShoppingTaskPolicy policy) {
        return policy.confirmationRequired()
                ? promptTemplateStore.text(REACT_TASK_POLICY_CONFIRMATION_REQUIRED)
                : "";
    }

    private String mallMcpFailureMessage(MallMcpToolResolutionException ex) {
        String message = safeMallMcpMessage(ex);
        return message.startsWith("商城 MCP 调用失败：") ? message : "商城 MCP 调用失败：" + message;
    }

    private String safeMallMcpMessage(RuntimeException ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return "mall-mcp 服务未启动或不可访问";
        }
        return ex.getMessage().trim();
    }

    private record ActiveToolCallbacks(List<ToolCallback> callbacks, boolean hasExternalTools, boolean hasMallTools) {

        private List<String> toolNames() {
            if (callbacks == null || callbacks.isEmpty()) {
                return List.of();
            }
            return callbacks.stream()
                    .filter(callback -> callback != null && callback.getToolDefinition() != null)
                    .map(callback -> callback.getToolDefinition().name())
                    .filter(StringUtils::hasText)
                    .toList();
        }

        private Set<String> toolNameSet() {
            if (callbacks == null || callbacks.isEmpty()) {
                return Set.of();
            }
            return callbacks.stream()
                    .filter(callback -> callback != null && callback.getToolDefinition() != null)
                    .map(callback -> callback.getToolDefinition().name())
                    .filter(StringUtils::hasText)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
    }

    private static ChatModel chatModelFromBuilder(ChatClient.Builder builder) {
        if (builder == null) {
            return null;
        }
        return new ChatClientBackedChatModel(builder.clone().build());
    }

    private static final class ChatClientBackedChatModel implements ChatModel {

        private final ChatClient chatClient;

        private ChatClientBackedChatModel(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (chatClient == null) {
                throw new IllegalStateException("ReActAgent ChatClient is not configured");
            }
            return prepareRequest(prompt).call().chatResponse();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (chatClient == null) {
                return Flux.error(new IllegalStateException("ReActAgent ChatClient is not configured"));
            }
            return prepareRequest(prompt).stream()
                    .content()
                    .map(this::chatResponse);
        }

        private ChatResponse chatResponse(String content) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }

        private ChatClient.ChatClientRequestSpec prepareRequest(Prompt prompt) {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt();
            if (prompt == null) {
                return request;
            }
            ChatOptions options = prompt.getOptions();
            if (options != null) {
                ChatClient.ChatClientRequestSpec updated = request.options(options);
                request = updated == null ? request : updated;
                if (options instanceof ToolCallingChatOptions toolOptions) {
                    updated = request.toolCallbacks(toolOptions.getToolCallbacks());
                    request = updated == null ? request : updated;
                    updated = request.toolContext(toolOptions.getToolContext());
                    request = updated == null ? request : updated;
                }
            }
            SystemMessage systemMessage = prompt.getSystemMessage();
            if (systemMessage != null) {
                ChatClient.ChatClientRequestSpec updated = request.system(systemMessage.getText());
                request = updated == null ? request : updated;
            }
            ChatClient.ChatClientRequestSpec advised = request.advisors(advisorSpec -> {
            });
            request = advised == null ? request : advised;
            List<Message> nonSystemMessages = prompt.getInstructions().stream()
                    .filter(message -> !(message instanceof SystemMessage))
                    .toList();
            ChatClient.ChatClientRequestSpec updated = request.messages(nonSystemMessages);
            return updated == null ? request : updated;
        }
    }

    private enum NoopAdvisorChain implements AdvisorChain {
        INSTANCE
    }

    private static final class MallMcpToolResolutionException extends RuntimeException {

        private MallMcpToolResolutionException(String message) {
            super(StringUtils.hasText(message) ? message : "mall-mcp 服务未启动或不可访问");
        }

        private MallMcpToolResolutionException(String message, Throwable cause) {
            super(StringUtils.hasText(message) ? message : "mall-mcp 服务未启动或不可访问", cause);
        }
    }

}
