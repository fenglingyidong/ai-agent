package com.example.ragagent.service;

import com.example.ragagent.memory.ConversationToolCallMemoryService;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.prompt.PromptTemplateStore;
import com.example.ragagent.tools.BuiltInTools;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 处理高置信度简单导购任务，必要时短路主 ReAct Agent。
 */
@Service
public class SimpleTaskAgent {

    private static final Logger log = LoggerFactory.getLogger(SimpleTaskAgent.class);
    private static final String EMPTY_KNOWLEDGE_MESSAGE = "商品知识库中没有检索到相关内容。";
    private static final String MALL_MCP_FAILURE_MESSAGE = "商城 MCP 调用失败：请稍后重试";
    private static final java.util.Set<String> SIMPLE_MALL_TOOL_NAMES = java.util.Set.of(
            "mall_search_products",
            "mall_get_product_detail",
            "mall_add_to_cart",
            "mall_view_cart",
            "mall_prepare_order"
    );

    private final ChatClient.Builder builder;
    private final BuiltInTools builtInTools;
    private final List<ToolCallbackProvider> toolCallbackProviders;
    private final RagTracing tracing;
    private final ConversationToolCallMemoryService toolCallMemoryService;
    private final String knowledgeSystemPrompt;
    private final String mallSystemPrompt;

    @Value("${app.ai.simple-task.enabled:true}")
    private boolean enabled = true;

    @Value("${app.ai.simple-task.model:${app.ai.intent-router.model:qwen3-vl-8b-instruct}}")
    private String model = "qwen3-vl-8b-instruct";

    @Value("${app.ai.simple-task.max-tokens:800}")
    private int maxTokens = 800;

    private ChatClient simpleTaskChatClient;

    public SimpleTaskAgent(ChatClient.Builder builder,
                           BuiltInTools builtInTools,
                           List<ToolCallbackProvider> toolCallbackProviders,
                           RagTracing tracing) {
        this(builder, builtInTools, toolCallbackProviders, tracing, null);
    }

    @Autowired
    public SimpleTaskAgent(ChatClient.Builder builder,
                           BuiltInTools builtInTools,
                           List<ToolCallbackProvider> toolCallbackProviders,
                           RagTracing tracing,
                           ConversationToolCallMemoryService toolCallMemoryService) {
        this.builder = builder;
        this.builtInTools = builtInTools;
        this.toolCallbackProviders = toolCallbackProviders == null ? List.of() : List.copyOf(toolCallbackProviders);
        this.tracing = tracing == null ? new RagTracing() : tracing;
        this.toolCallMemoryService = toolCallMemoryService;
        PromptTemplateStore store = new PromptTemplateStore();
        this.knowledgeSystemPrompt = store.text("simple-task.knowledge.system");
        this.mallSystemPrompt = store.text("simple-task.mall.system");
    }

    /**
     * 初始化简单任务专用 ChatClient，复用全局模型连接配置。
     */
    @PostConstruct
    public void init() {
        if (simpleTaskChatClient == null && builder != null) {
            this.simpleTaskChatClient = builder.clone().build();
        }
    }

    /**
     * 尝试用简单任务模型处理路由结果，不满足条件时交还主链路。
     */
    FastLaneResult tryRun(ShoppingIntentRoute route,
                          String userMessage,
                          String sessionId,
                          double confidenceThreshold) {
        return tryRun(route, userMessage, sessionId, confidenceThreshold, "");
    }

    /**
     * 尝试用简单任务模型处理路由结果，并附带当前会话导购偏好上下文。
     */
    FastLaneResult tryRun(ShoppingIntentRoute route,
                          String userMessage,
                          String sessionId,
                          double confidenceThreshold,
                          String preferenceContext) {
        return tryRun(route, userMessage, "", sessionId, confidenceThreshold, preferenceContext, "", "", "");
    }

    /**
     * 尝试用简单任务模型处理路由结果，并把商城认证上下文传给 MCP meta。
     */
    FastLaneResult tryRun(ShoppingIntentRoute route,
                          String userMessage,
                          String sessionId,
                          double confidenceThreshold,
                          String preferenceContext,
                          String mallToken,
                          String mallUsername,
                          String mallPassword) {
        return tryRun(route, userMessage, "", sessionId, confidenceThreshold, preferenceContext,
                mallToken, mallUsername, mallPassword);
    }

    /**
     * 尝试用简单任务模型处理路由结果，并把应用用户身份与商城认证上下文传给工具窗口。
     */
    FastLaneResult tryRun(ShoppingIntentRoute route,
                          String userMessage,
                          String userId,
                          String sessionId,
                          double confidenceThreshold,
                          String preferenceContext,
                          String mallToken,
                          String mallUsername,
                          String mallPassword) {
        if (!enabled || route == null || !route.isHighConfidence(confidenceThreshold)
                || Boolean.TRUE.equals(route.routeToCore())) {
            return FastLaneResult.notHandled();
        }
        if (simpleTaskChatClient == null) {
            return FastLaneResult.fallbackToCore("简单任务小模型不可用");
        }

        return switch (route.normalizedTaskType()) {
            case "FAQ_SIMPLE_QUERY" -> runKnowledgeTask(route, userMessage, userId, sessionId, preferenceContext);
            case "SIMPLE_SHOPPING_TOOL" -> runMallTask(route, userMessage, sessionId, preferenceContext,
                    userId, mallToken, mallUsername, mallPassword);
            default -> FastLaneResult.notHandled();
        };
    }

    private FastLaneResult runKnowledgeTask(ShoppingIntentRoute route,
                                            String userMessage,
                                            String userId,
                                            String sessionId,
                                            String preferenceContext) {
        if (builtInTools == null) {
            return FastLaneResult.fallbackToCore("RAG 检索工具不可用");
        }
        return callSimpleModelWithToolObject(
                route,
                userMessage,
                preferenceContext,
                knowledgeSystemPrompt,
                new KnowledgeFastLaneTools(builtInTools, toolCallMemoryService, userId, sessionId)
        );
    }

    private FastLaneResult runMallTask(ShoppingIntentRoute route,
                                       String userMessage,
                                       String sessionId,
                                       String preferenceContext,
                                       String userId,
                                       String mallToken,
                                       String mallUsername,
                                       String mallPassword) {
        if (!StringUtils.hasText(sessionId)) {
            return FastLaneResult.fallbackToCore("简单商城任务缺少 sessionId");
        }
        List<ToolCallback> callbacks;
        try {
            callbacks = simpleMallToolCallbacks(userId, sessionId);
        }
        catch (McpUnavailableException ex) {
            return mallMcpFailure(ex);
        }
        if (callbacks.isEmpty()) {
            return mallMcpFailure("MallMcpToolUnavailable");
        }
        return callSimpleModelWithToolCallbacks(
                route,
                userMessage,
                preferenceContext,
                mallSystemPrompt,
                callbacks,
                mallToolContext(userId, sessionId, mallToken, mallUsername, mallPassword)
        );
    }

    private List<ToolCallback> simpleMallToolCallbacks(String userId, String sessionId) {
        try {
            List<ToolCallback> callbacks = new ArrayList<>();
            for (ToolCallbackProvider provider : toolCallbackProviders) {
                ToolCallback[] providedCallbacks = provider.getToolCallbacks();
                if (providedCallbacks == null) {
                    continue;
                }
                for (ToolCallback callback : providedCallbacks) {
                    String name = toolName(callback);
                    if (SIMPLE_MALL_TOOL_NAMES.contains(name)) {
                        callbacks.add(new LoggingToolCallback(
                                new MallRenderedToolCallback(callback, new MallToolResultRenderer()),
                                userId,
                                sessionId,
                                tracing,
                                toolCallMemoryService
                        ));
                    }
                }
            }
            return List.copyOf(callbacks);
        }
        catch (RuntimeException ex) {
            throw new McpUnavailableException("mall-mcp 服务未启动或不可访问", ex);
        }
    }

    private Map<String, Object> mallToolContext(String userId,
                                                String sessionId,
                                                String mallToken,
                                                String mallUsername,
                                                String mallPassword) {
        java.util.LinkedHashMap<String, Object> context = new java.util.LinkedHashMap<>();
        putIfText(context, "userId", userId);
        context.put("sessionId", sessionId.trim());
        putIfText(context, "mallToken", mallToken);
        putIfText(context, "mallUsername", mallUsername);
        putIfText(context, "mallPassword", mallPassword);
        return Map.copyOf(context);
    }

    private void putIfText(Map<String, Object> context, String key, String value) {
        if (StringUtils.hasText(value)) {
            context.put(key, value.trim());
        }
    }

    private FastLaneResult callSimpleModelWithToolObject(ShoppingIntentRoute route,
                                                         String userMessage,
                                                         String preferenceContext,
                                                         String systemPrompt,
                                                         Object toolObject) {
        String userPrompt = buildUserPrompt(route, userMessage, preferenceContext);
        return callSimpleModel(route, systemPrompt, userPrompt, () -> simpleTaskChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                        .model(modelName())
                        .temperature(0.0)
                        .maxTokens(Math.max(1, maxTokens))
                        .build())
                .system(systemPrompt)
                .user(userPrompt)
                .tools(toolObject)
                .call()
                .content());
    }

    private FastLaneResult callSimpleModelWithToolCallbacks(ShoppingIntentRoute route,
                                                            String userMessage,
                                                            String preferenceContext,
                                                            String systemPrompt,
                                                            List<ToolCallback> toolCallbacks,
                                                            Map<String, Object> toolContext) {
        String userPrompt = buildUserPrompt(route, userMessage, preferenceContext);
        return callSimpleModel(route, systemPrompt, userPrompt, () -> simpleTaskChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                        .model(modelName())
                        .temperature(0.0)
                        .maxTokens(Math.max(1, maxTokens))
                        .build())
                .system(systemPrompt)
                .user(userPrompt)
                .toolCallbacks(toolCallbacks)
                .toolContext(toolContext)
                .call()
                .content());
    }

    private FastLaneResult callSimpleModel(ShoppingIntentRoute route,
                                           String systemPrompt,
                                           String userPrompt,
                                           Supplier<String> modelCall) {
        return tracing.inSpan("llm.simple_task", () -> callSimpleModelInCurrentSpan(
                route,
                systemPrompt,
                userPrompt,
                modelCall
        ));
    }

    private FastLaneResult callSimpleModelInCurrentSpan(ShoppingIntentRoute route,
                                                       String systemPrompt,
                                                       String userPrompt,
                                                       Supplier<String> modelCall) {
        try {
            Span span = tracing.currentSpan();
            tracing.capturePromptText(span, "llm.simple_task.input", llmInput(systemPrompt, userPrompt));
            log.info("简单任务小模型开始：taskType={}, intent={}, model={}",
                    route.normalizedTaskType(), route.normalizedIntent(), modelName());
            String content = modelCall.get();
            tracing.capturePromptText(span, "llm.simple_task.output", content);
            if (!StringUtils.hasText(content)) {
                return FastLaneResult.fallbackToCore("简单任务小模型返回空内容");
            }
            log.info("简单任务小模型完成：taskType={}, intent={}", route.normalizedTaskType(), route.normalizedIntent());
            return FastLaneResult.handled(content.trim());
        }
        catch (McpUnavailableException ex) {
            return mallMcpFailure(ex);
        }
        catch (FastLaneFallbackException ex) {
            log.info("简单任务小模型降级主 Agent：{}", ex.getMessage());
            return FastLaneResult.fallbackToCore(ex.getMessage());
        }
        catch (RuntimeException ex) {
            if (isMcpUnavailable(ex)) {
                return mallMcpFailure(ex);
            }
            log.warn("简单任务小模型失败，降级主 Agent：{}", ex.getMessage());
            log.debug("简单任务小模型异常堆栈", ex);
            return FastLaneResult.fallbackToCore("简单任务小模型失败：" + safeMessage(ex));
        }
    }

    private String buildUserPrompt(ShoppingIntentRoute route, String userMessage, String preferenceContext) {
        String routePrompt = """
                用户本轮输入：
                %s

                路由任务类型：%s
                路由意图：%s
                路由置信度：%.2f
                文本槽位：%s
                视觉槽位：%s
                路由原因：%s
                """.formatted(
                StringUtils.hasText(userMessage) ? userMessage.trim() : "",
                route.normalizedTaskType(),
                route.normalizedIntent(),
                route.confidence(),
                renderSlots(route.textSlots()),
                renderSlots(route.visualContext()),
                route.reason()
        ).trim();
        if (!StringUtils.hasText(preferenceContext)) {
            return routePrompt;
        }
        return preferenceContext.trim() + System.lineSeparator() + System.lineSeparator() + routePrompt;
    }

    private String renderSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        slots.forEach((key, value) -> {
            String text = value == null ? "" : value.toString().trim();
            if (StringUtils.hasText(key) && StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
                parts.add(key.trim() + "=" + text);
            }
        });
        return parts.isEmpty() ? "无" : String.join("，", parts);
    }

    private String modelName() {
        return StringUtils.hasText(model) ? model.trim() : "qwen3-vl-8b-instruct";
    }

    private boolean isMcpUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof McpUnavailableException) {
                return true;
            }
            if (current instanceof ToolExecutionException toolExecutionException
                    && MallTool.isMallTool(toolName(toolExecutionException.getToolDefinition()))) {
                return true;
            }
            String message = current.getMessage();
            if (StringUtils.hasText(message) && message.contains("mall-mcp 服务未启动或不可访问")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String toolName(ToolCallback callback) {
        return callback == null ? null : toolName(callback.getToolDefinition());
    }

    private String toolName(ToolDefinition definition) {
        return definition == null || !StringUtils.hasText(definition.name()) ? null : definition.name();
    }

    private FastLaneResult mallMcpFailure(Throwable ex) {
        logMcpUnavailable(ex);
        return FastLaneResult.handled(MALL_MCP_FAILURE_MESSAGE);
    }

    private FastLaneResult mallMcpFailure(String errorType) {
        log.warn("简单商城任务 MCP 不可用：errorType={}, toolName={}",
                StringUtils.hasText(errorType) ? errorType : "<unknown>",
                "<unknown>");
        return FastLaneResult.handled(MALL_MCP_FAILURE_MESSAGE);
    }

    private void logMcpUnavailable(Throwable ex) {
        log.warn("简单商城任务 MCP 不可用：errorType={}, toolName={}", errorType(ex), mallToolName(ex));
        log.debug("简单商城任务 MCP 异常堆栈", ex);
    }

    private String errorType(Throwable ex) {
        return ex == null ? "<unknown>" : ex.getClass().getSimpleName();
    }

    private String mallToolName(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ToolExecutionException toolExecutionException) {
                String toolName = toolName(toolExecutionException.getToolDefinition());
                if (MallTool.isMallTool(toolName)) {
                    return toolName;
                }
            }
            current = current.getCause();
        }
        return "<unknown>";
    }

    private String safeMessage(Throwable ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return "unknown error";
        }
        String message = ex.getMessage().trim();
        if (message.startsWith("商城 MCP 调用失败：")) {
            return message.substring("商城 MCP 调用失败：".length());
        }
        return message;
    }

    /**
     * 暴露给简单知识库任务模型的受限 RAG 工具集合。
     */
    static final class KnowledgeFastLaneTools {

        private final BuiltInTools builtInTools;
        private final ConversationToolCallMemoryService toolCallMemoryService;
        private final String userId;
        private final String sessionId;

        KnowledgeFastLaneTools(BuiltInTools builtInTools) {
            this(builtInTools, null, "", "");
        }

        KnowledgeFastLaneTools(BuiltInTools builtInTools,
                               ConversationToolCallMemoryService toolCallMemoryService,
                               String userId,
                               String sessionId) {
            this.builtInTools = builtInTools;
            this.toolCallMemoryService = toolCallMemoryService;
            this.userId = userId;
            this.sessionId = sessionId;
        }

        /**
         * 检索商品知识库；空结果或异常会触发回退主 Agent。
         */
        @Tool(description = "检索商品知识库中的商品特点、规格、适用年龄、评价摘要和导购知识。实时价格、库存、购物车和订单信息不能用这个工具回答。")
        public String searchProductKnowledge(
                @ToolParam(description = "用户想查询的商品知识、商品名称或问题", required = true) String query) {
            try {
                String result = builtInTools.searchProductKnowledge(query);
                if (!StringUtils.hasText(result) || result.contains(EMPTY_KNOWLEDGE_MESSAGE)) {
                    throw new FastLaneFallbackException("A 类 RAG 检索为空");
                }
                String trimmedResult = result.trim();
                rememberSuccess(query, trimmedResult);
                return trimmedResult;
            }
            catch (FastLaneFallbackException ex) {
                rememberError(query, ex);
                throw ex;
            }
            catch (RuntimeException ex) {
                FastLaneFallbackException fallback = new FastLaneFallbackException(
                        "A 类 RAG 检索失败",
                        ex
                );
                rememberError(query, fallback);
                throw fallback;
            }
        }

        private void rememberSuccess(String input, String output) {
            if (toolCallMemoryService == null) {
                return;
            }
            try {
                toolCallMemoryService.rememberSuccess(userId, sessionId, "searchProductKnowledge", input, output);
            }
            catch (RuntimeException ignored) {
                // 工具窗口是旁路上下文，不能改变快车道工具返回值。
            }
        }

        private void rememberError(String input, FastLaneFallbackException ex) {
            if (toolCallMemoryService == null) {
                return;
            }
            try {
                toolCallMemoryService.rememberError(userId, sessionId, "searchProductKnowledge", input, ex);
            }
            catch (RuntimeException ignored) {
                // 工具窗口是旁路上下文，不能改变快车道回退行为。
            }
        }
    }

    /**
     * 表示简单任务链路应放弃短路，回退给主 ReAct Agent。
     */
    static class FastLaneFallbackException extends RuntimeException {
        FastLaneFallbackException(String message) {
            super(message);
        }

        FastLaneFallbackException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 统一标记商城 MCP 不可用，避免把底层异常细节暴露给用户。
     */
    static class McpUnavailableException extends RuntimeException {
        McpUnavailableException(String message) {
            super(StringUtils.hasText(message) ? message : "mall-mcp 服务未启动或不可访问");
        }

        McpUnavailableException(String message, Throwable cause) {
            super(StringUtils.hasText(message) ? message : "mall-mcp 服务未启动或不可访问", cause);
        }
    }

    private String llmInput(String systemPrompt, String userPrompt) {
        return "system:\n" + systemPrompt + "\n\nuser:\n" + userPrompt;
    }
}
