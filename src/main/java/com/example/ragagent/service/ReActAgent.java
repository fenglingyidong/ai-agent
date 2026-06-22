package com.example.ragagent.service;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.conversation.ConversationTurnRecord;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.ConversationToolCallMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.prompt.PromptTemplateStore;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.tools.BuiltInTools;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
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

/**
 * 复杂 ReAct 主代理入口，负责路由、工具装配、提示词构建、流式模型调用和会话落库。
 */
@Service
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final String TOOL_CONTEXT_USER_ID = "userId";
    private static final String TOOL_CONTEXT_SESSION_ID = "sessionId";

    private final ConversationMemoryService conversationMemoryService;
    private final ConversationToolCallMemoryService toolCallMemoryService;
    private final ConversationLogService conversationLogService;
    private final PromptSecurityFilter promptSecurityFilter;
    private final ChatModelRegistry chatModelRegistry;
    private final ShoppingRouteExecutor shoppingRouteExecutor;
    private final ReactToolResolver toolResolver;
    private final RagTracing tracing;
    private final ReactPromptBuilder promptBuilder;
    private final ReactStreamExecutor streamExecutor;

    @Autowired
    public ReActAgent(ChatModel chatModel,
                      BuiltInTools builtInTools,
                      LongTermMemoryAdvisor longTermMemoryAdvisor,
                      MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                      ConversationMemoryService conversationMemoryService,
                      ConversationToolCallMemoryService toolCallMemoryService,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry,
                      ShoppingRouteExecutor shoppingRouteExecutor,
                      List<ToolCallbackProvider> externalToolCallbackProviders,
                      ConversationLogService conversationLogService,
                      RagTracing tracing,
                      PromptTemplateStore promptTemplateStore) {
        this.conversationMemoryService = conversationMemoryService;
        this.toolCallMemoryService = toolCallMemoryService;
        this.conversationLogService = conversationLogService;
        this.promptSecurityFilter = promptSecurityFilter;
        this.chatModelRegistry = chatModelRegistry;
        this.shoppingRouteExecutor = shoppingRouteExecutor;
        this.tracing = tracing == null ? new RagTracing() : tracing;
        this.promptBuilder = new ReactPromptBuilder(promptTemplateStore);
        this.streamExecutor = new ReactStreamExecutor(
                chatModel,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                conversationLogService,
                this.tracing,
                this.promptBuilder
        );
        this.toolResolver = new ReactToolResolver(
                builtInTools,
                externalToolCallbackProviders,
                this.tracing,
                this.toolCallMemoryService
        );
    }

    /**
     * 执行一次 /api/react 流式会话请求，并返回可直接写给前端的文本流。
     */
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
        Span requestSpan = tracing.startSpan("POST /api/react");
        ConversationTurnRecord conversationTurn = null;
        try (Scope ignored = tracing.makeCurrent(requestSpan)) {
            // /api/react 的主入口：记录请求 trace 和会话 turn，再按路由结果进入快车道或复杂 ReAct 主链路。
            writeRequestTraceAttributes(userId, sessionId, modelId, webSearchEnabled, safeMedia.size());
            conversationTurn = conversationLogService.beginTurn(
                    userId,
                    sessionId,
                    modelId,
                    webSearchEnabled,
                    userMessage,
                    safeMedia.size()
            );
            tracing.recordTraceInput(requestSpan, userMessage);
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
                // A/B 快车道已经由 ShoppingRouteExecutor 得到答案流；这里仅恢复敏感值并补齐记忆/流水。
                return finishRequestSpan(rememberShortCircuitTurn(
                        userId,
                        sessionId,
                        routedRequest.userMessage(),
                        routedRequest.shortCircuitStream(),
                        preRouteSecuredPrompt,
                        conversationTurn,
                        requestSpan
                ), requestSpan);
            }

            PromptSecurityFilter.SecuredPrompt securedPrompt = promptSecurityFilter.secure(
                    routedRequest.userMessage(),
                    preRouteSecuredPrompt.sensitiveValues()
            );
            // C_COMPLEX_REACT 或无法短路的请求进入复杂主链路：解析工具、构造 prompt，再流式调用模型。
            return finishRequestSpan(runCoreStream(
                    userId,
                    sessionId,
                    modelId,
                    webSearchEnabled,
                    routedRequest.media(),
                    routedRequest.mallToolsAllowed(),
                    routedRequest.taskPolicies(),
                    routedRequest.orderCreationAllowed(),
                    routedRequest.trustedContext(),
                    mallToken,
                    mallUsername,
                    mallPassword,
                    securedPrompt,
                    conversationTurn,
                    requestSpan
            ), requestSpan);
        }
        catch (RuntimeException ex) {
            tracing.recordError(requestSpan, ex);
            if (conversationTurn != null) {
                conversationLogService.failTurn(conversationTurn, "", ex);
            }
            tracing.endSpan(requestSpan);
            throw ex;
        }
    }

    private Flux<String> finishRequestSpan(Flux<String> stream, Span requestSpan) {
        AtomicBoolean ended = new AtomicBoolean(false);
        Flux<String> safeStream = stream == null ? Flux.empty() : stream;
        return safeStream
                .doOnError(ex -> tracing.recordError(requestSpan, ex))
                .doFinally(signalType -> {
                    if (ended.compareAndSet(false, true)) {
                        tracing.endSpan(requestSpan);
                    }
                });
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
        // 快车道不再经过 ReAct 大模型，但对外仍保持同样的输出恢复、记忆写入和会话流水语义。
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
                                          String mallToken,
                                          String mallUsername,
                                          String mallPassword,
                                          PromptSecurityFilter.SecuredPrompt securedPrompt,
                                          ConversationTurnRecord conversationTurn,
                                          Span rootSpan) {
        // 复杂主链路第一步：根据路由结果得到本轮真正可用的工具，并套上 tracing/下单门禁等 Java 侧保护。
        ReactToolResolver.ActiveToolCallbacks activeTools;
        try {
            activeTools = toolResolver.resolve(
                    userId,
                    sessionId,
                    webSearchEnabled,
                    mallToolsAllowed,
                    taskPolicies,
                    orderCreationAllowed
            );
        }
        catch (ReactToolResolver.MallMcpToolResolutionException ex) {
            return rememberToolResolutionFailure(userId, sessionId, securedPrompt, conversationTurn, rootSpan, ex);
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

        // 复杂主链路第二步：把净化后的用户文本和图片组装成当前轮 UserMessage。
        return streamLlmResponse(
                userId,
                sessionId,
                modelId,
                currentUserMessage,
                activeTools,
                webSearchEnabled,
                taskPolicies,
                trustedContext,
                mallToken,
                mallUsername,
                mallPassword,
                securedPrompt,
                conversationTurn,
                rootSpan
        );
    }

    private Flux<String> rememberToolResolutionFailure(String userId,
                                                       String sessionId,
                                                        PromptSecurityFilter.SecuredPrompt securedPrompt,
                                                        ConversationTurnRecord conversationTurn,
                                                        Span rootSpan,
                                                        ReactToolResolver.MallMcpToolResolutionException ex) {
        // mall-mcp 工具发现失败属于工具层失败，直接返回可见失败信息，并保留本轮记忆/流水。
        String answer = toolResolver.mallMcpFailureMessage(ex);
        return Flux.just(answer)
                .doOnComplete(() -> {
                    conversationMemoryService.rememberTurn(
                            userId,
                            sessionId,
                            securedPrompt.modelInput(),
                            answer
                    );
                    tracing.recordTraceOutput(rootSpan, answer);
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
                                           ReactToolResolver.ActiveToolCallbacks activeTools,
                                           boolean webSearchEnabled,
                                           List<ShoppingTaskPolicy> taskPolicies,
                                           String trustedContext,
                                           String mallToken,
                                           String mallUsername,
                                           String mallPassword,
                                            PromptSecurityFilter.SecuredPrompt securedPrompt,
                                            ConversationTurnRecord conversationTurn,
                                            Span rootSpan) {
        // 复杂主链路第三步：渲染 system prompt，绑定工具上下文和 advisor 上下文，形成一次 ChatModel 请求。
        String reactSystemPrompt = promptBuilder.buildSystemPrompt(
                activeTools.hasExternalTools(),
                activeTools.hasMallTools(),
                webSearchEnabled,
                taskPolicies,
                activeTools.toolNameSet(),
                coreTrustedContext(userId, sessionId, trustedContext)
        );
        String conversationId = conversationMemoryService.buildConversationId(userId, sessionId);
        Map<String, Object> toolContext = buildToolContext(userId, sessionId, mallToken, mallUsername, mallPassword);
        ChatClientRequest baseRequest = buildChatClientRequest(
                modelId,
                reactSystemPrompt,
                currentUserMessage,
                activeTools,
                toolContext,
                buildAdvisorContext(userId, sessionId, conversationId)
        );

        // 复杂主链路第四步：模型流、advisor、敏感值恢复、fallback、llm.react span 由 ReactStreamExecutor 收口。
        return streamExecutor.execute(new ReactStreamExecutor.ReactStreamRequest(
                userId,
                sessionId,
                modelId,
                baseRequest,
                activeTools.toolNames(),
                securedPrompt,
                conversationTurn,
                rootSpan
        ));
    }

    private ChatClientRequest buildChatClientRequest(String modelId,
                                                     String systemPrompt,
                                                     Message currentUserMessage,
                                                     ReactToolResolver.ActiveToolCallbacks activeTools,
                                                     Map<String, Object> toolContext,
                                                     Map<String, Object> advisorContext) {
        // ChatModel 所需的 Prompt 同时携带 system/user messages、工具回调和工具上下文。
        Prompt prompt = Prompt.builder()
                .messages(List.of(new SystemMessage(systemPrompt), currentUserMessage))
                .chatOptions(buildModelOptions(modelId, activeTools, toolContext))
                .build();
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(advisorContext)
                .build();
    }

    private String coreTrustedContext(String userId, String sessionId, String trustedContext) {
        String toolContext = conversationMemoryService.recentToolCallContext(userId, sessionId);
        if (!StringUtils.hasText(trustedContext)) {
            return StringUtils.hasText(toolContext) ? toolContext.trim() : "";
        }
        if (!StringUtils.hasText(toolContext)) {
            return trustedContext.trim();
        }
        return trustedContext.trim() + System.lineSeparator() + System.lineSeparator() + toolContext.trim();
    }

    private OpenAiChatOptions buildModelOptions(String modelId,
                                                ReactToolResolver.ActiveToolCallbacks activeTools,
                                                Map<String, Object> toolContext) {
        OpenAiChatOptions selectedOptions = chatModelRegistry == null
                ? null
                : chatModelRegistry.createOptions(modelId);
        OpenAiChatOptions options = selectedOptions == null
                ? OpenAiChatOptions.builder().streamUsage(true).build()
                : selectedOptions.copy();
        options.setStreamUsage(true);
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

    private Map<String, Object> buildToolContext(String userId,
                                                 String sessionId,
                                                 String mallToken,
                                                 String mallUsername,
                                                 String mallPassword) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(TOOL_CONTEXT_USER_ID, StringUtils.hasText(userId) ? userId.trim() : "");
        context.put(TOOL_CONTEXT_SESSION_ID, StringUtils.hasText(sessionId) ? sessionId.trim() : "");
        putIfText(context, "mallToken", mallToken);
        putIfText(context, "mallUsername", mallUsername);
        putIfText(context, "mallPassword", mallPassword);
        context.put(BuiltInTools.TOOL_CONTEXT_SEARCH_PRODUCT_KNOWLEDGE_CACHE, new ConcurrentHashMap<String, String>());
        return context;
    }

    private void putIfText(Map<String, Object> context, String key, String value) {
        if (StringUtils.hasText(value)) {
            context.put(key, value.trim());
        }
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

    private int mediaCount(List<Media> media) {
        return media == null ? 0 : media.size();
    }

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

}
