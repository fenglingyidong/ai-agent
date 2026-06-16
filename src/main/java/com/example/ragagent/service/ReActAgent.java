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
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
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
    private final ChatClient reactChatClient;
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

    @Autowired
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
        this.reactChatClient = builder.clone().build();
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
                    .handle((chunk, sink) -> {
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
                            }))
                    .cast(String.class);
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
                                           PromptSecurityFilter.SecuredPrompt securedPrompt,
                                           ConversationTurnRecord conversationTurn,
                                           Span rootSpan) {
        ChatClient.ChatClientRequestSpec requestSpec = applyModelOptions(reactChatClient.prompt(), modelId);
        String reactSystemPrompt = buildReactSystemPrompt(
                activeTools.hasExternalTools(),
                activeTools.hasMallTools(),
                webSearchEnabled,
                taskPolicies
        );
        ChatClient.ChatClientRequestSpec requestWithSystem =
                requestSpec.system(reactSystemPrompt);
        if (requestWithSystem == null) {
            requestWithSystem = requestSpec;
        }

        ChatClient.ChatClientRequestSpec requestWithTools = requestWithSystem.toolCallbacks(activeTools.callbacks());
        if (requestWithTools == null) {
            requestWithTools = requestWithSystem;
        }
        ChatClient.ChatClientRequestSpec requestWithToolContext = requestWithTools.toolContext(
                buildToolContext(userId, sessionId)
        );
        if (requestWithToolContext == null) {
            requestWithToolContext = requestWithTools;
        }
        String conversationId = conversationMemoryService.buildConversationId(userId, sessionId);
        ChatClient.ChatClientRequestSpec requestWithMemory = requestWithToolContext.advisors(advisorSpec -> advisorSpec
                .param(ChatMemory.CONVERSATION_ID, conversationId)
                .param(LongTermMemoryAdvisor.USER_ID_KEY, userId)
                .param(LongTermMemoryAdvisor.SESSION_ID_KEY, sessionId)
                .advisors(longTermMemoryAdvisor, messageChatMemoryAdvisor));
        if (requestWithMemory == null) {
            requestWithMemory = requestWithToolContext;
        }
        ChatClient.ChatClientRequestSpec finalRequestWithMemory = requestWithMemory;

        return Flux.defer(() -> {
            Span span = tracing.startSpan("llm.react");
            Scope scope = tracing.makeCurrent(span);
            tracing.capturePromptText(span, "llm.react.input", reactInput(reactSystemPrompt, currentUserMessage, activeTools));
            StreamingSensitiveValueRestorer restorer =
                    new StreamingSensitiveValueRestorer(securedPrompt.sensitiveValues());
            StringBuilder rawAnswerBuilder = new StringBuilder();
            StringBuilder restoredAnswerBuilder = new StringBuilder();
            AtomicBoolean fallbackUsed = new AtomicBoolean(false);
            AtomicBoolean persisted = new AtomicBoolean(false);

            try {
                Flux<String> restoredStream = finalRequestWithMemory
                        .messages(List.of(currentUserMessage))
                        .stream()
                        .content()
                        .handle((chunk, sink) -> {
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
                                }))
                        .cast(String.class);

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
                return Flux.error(ex);
            }
        });
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

    private ChatClient.ChatClientRequestSpec applyModelOptions(ChatClient.ChatClientRequestSpec requestSpec, String modelId) {
        if (chatModelRegistry == null) {
            return requestSpec;
        }
        OpenAiChatOptions options = chatModelRegistry.createOptions(modelId);
        return options == null ? requestSpec : requestSpec.options(options);
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
                || "updateShoppingPreference".equals(toolName)
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
                                          List<ShoppingTaskPolicy> taskPolicies) {
        String mallRule = hasMallTools
                ? promptTemplateStore.text(REACT_MALL_RULE_ENABLED)
                : promptTemplateStore.text(REACT_MALL_RULE_DISABLED);
        String networkRule = webSearchEnabled && hasExternalTools
                ? promptTemplateStore.text(REACT_NETWORK_RULE_ENABLED)
                : promptTemplateStore.text(REACT_NETWORK_RULE_DISABLED);
        String policyPrompt = renderTaskPolicyPrompt(taskPolicies);
        return promptTemplateStore.render(REACT_SYSTEM_PROMPT, Map.of(
                "mall_rule", mallRule,
                "network_rule", networkRule,
                "task_policy_prompt", StringUtils.hasText(policyPrompt)
                        ? System.lineSeparator() + System.lineSeparator() + policyPrompt
                        : ""
        ));
    }

    private String reactInput(String systemPrompt, Message currentUserMessage, ActiveToolCallbacks activeTools) {
        return "system:\n" + systemPrompt
                + "\n\nuser:\n" + messageText(currentUserMessage)
                + "\n\nmedia_count: " + mediaCountFromMessage(currentUserMessage)
                + "\ntools: " + activeTools.toolNames();
    }

    private String messageText(Message message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.getText();
        }
        return message == null ? "" : String.valueOf(message);
    }

    private int mediaCountFromMessage(Message message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.getMedia().size();
        }
        return 0;
    }

    private String renderTaskPolicyPrompt(List<ShoppingTaskPolicy> taskPolicies) {
        if (taskPolicies == null || taskPolicies.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(promptTemplateStore.text(REACT_TASK_POLICY_HEADER));
        for (ShoppingTaskPolicy policy : taskPolicies) {
            builder.append(System.lineSeparator())
                    .append(renderTaskPolicyItem(policy));
        }
        return builder.toString();
    }

    private String renderTaskPolicyItem(ShoppingTaskPolicy policy) {
        return promptTemplateStore.render(REACT_TASK_POLICY_ITEM, Map.of(
                "policy_id", policy.id(),
                "policy_name", policy.name(),
                "policy_prompt", policy.promptFragment(),
                "allowed_tools_text", renderAllowedToolsText(policy),
                "confirmation_required_text", renderConfirmationRequiredText(policy)
        ));
    }

    private String renderAllowedToolsText(ShoppingTaskPolicy policy) {
        if (policy.allowedToolNames().isEmpty()) {
            return "";
        }
        return promptTemplateStore.render(REACT_TASK_POLICY_ALLOWED_TOOLS, Map.of(
                "allowed_tools", String.join(", ", policy.allowedToolNames())
        ));
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
