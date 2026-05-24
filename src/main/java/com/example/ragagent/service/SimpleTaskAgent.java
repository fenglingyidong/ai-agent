package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpClient;
import com.example.ragagent.tools.BuiltInTools;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class SimpleTaskAgent {

    private static final Logger log = LoggerFactory.getLogger(SimpleTaskAgent.class);
    private static final String EMPTY_KNOWLEDGE_MESSAGE = "商品知识库中没有检索到相关内容。";
    private static final Set<String> SIMPLE_MALL_INTENTS = Set.of(
            "PRICE_STOCK_QUERY",
            "QUERY_ATTRIBUTE",
            "VIEW_CART",
            "ADD_TO_CART",
            "PREPARE_ORDER"
    );
    private static final Set<String> SIMPLE_MALL_TOOL_NAMES = Set.of(
            "mall_search_products",
            "mall_get_product_detail",
            "mall_add_to_cart",
            "mall_view_cart",
            "mall_prepare_order"
    );

    private static final String KNOWLEDGE_SYSTEM_PROMPT = """
            你是电商导购的简单知识库问答助手。
            必须先调用 searchProductKnowledge 工具，再根据工具结果简短回答。
            不得编造工具结果之外的商品特点、价格、库存、优惠或订单信息。
            工具没有结果或工具失败时，不要自行猜测。
            输出纯文本中文回复，不要暴露工具名、JSON 或内部路由字段。
            """;

    private static final String MALL_SYSTEM_PROMPT = """
            你是电商导购的简单商城任务助手。
            必须使用提供的 mall_* MCP 工具完成任务，再根据工具结果简短回答。
            搜索商品时调用 mall_search_products；查价格、库存、属性和详情时调用 mall_get_product_detail。
            明确加购物车时调用 mall_add_to_cart；查看购物车时调用 mall_view_cart；确认订单摘要时调用 mall_prepare_order。
            不允许创建订单、付款或编造 confirmationId；不得调用 mall_create_order，遇到确认下单应交给复杂任务。
            价格、库存、购物车和订单摘要只能来自工具结果。
            如果工具返回“商城 MCP 调用失败”，必须原样说明调用失败。
            输出纯文本中文回复，不要暴露工具名、JSON 或内部路由字段。
            """;

    private final ChatClient.Builder builder;
    private final BuiltInTools builtInTools;
    private final MallMcpToolCallback mallMcpToolCallback;

    @Value("${app.ai.simple-task.enabled:true}")
    private boolean enabled = true;

    @Value("${app.ai.simple-task.model:${app.ai.intent-router.model:qwen3-vl-8b-instruct}}")
    private String model = "qwen3-vl-8b-instruct";

    @Value("${app.ai.simple-task.max-tokens:800}")
    private int maxTokens = 800;

    private ChatClient simpleTaskChatClient;

    @Autowired
    public SimpleTaskAgent(ChatClient.Builder builder,
                           BuiltInTools builtInTools,
                           MallMcpClient mallMcpClient) {
        this.builder = builder;
        this.builtInTools = builtInTools;
        this.mallMcpToolCallback = mallMcpClient == null ? null : new MallMcpToolCallback(mallMcpClient);
    }

    SimpleTaskAgent(ChatClient simpleTaskChatClient,
                    BuiltInTools builtInTools,
                    MallMcpClient mallMcpClient) {
        this.builder = null;
        this.simpleTaskChatClient = simpleTaskChatClient;
        this.builtInTools = builtInTools;
        this.mallMcpToolCallback = mallMcpClient == null ? null : new MallMcpToolCallback(mallMcpClient);
    }

    @PostConstruct
    public void init() {
        if (simpleTaskChatClient == null && builder != null) {
            this.simpleTaskChatClient = builder.clone().build();
        }
    }

    FastLaneResult tryRun(ShoppingIntentRoute route,
                          String userMessage,
                          String sessionId,
                          double confidenceThreshold) {
        if (!enabled || route == null || !route.isHighConfidence(confidenceThreshold)
                || Boolean.TRUE.equals(route.routeToCore())) {
            return FastLaneResult.notHandled();
        }
        if (simpleTaskChatClient == null) {
            return FastLaneResult.fallbackToCore("简单任务小模型不可用");
        }

        return switch (route.normalizedTaskType()) {
            case "A_FAQ_SIMPLE_QUERY" -> runKnowledgeTask(route, userMessage);
            case "B_SIMPLE_SHOPPING_TOOL" -> runMallTask(route, userMessage, sessionId);
            default -> FastLaneResult.notHandled();
        };
    }

    private FastLaneResult runKnowledgeTask(ShoppingIntentRoute route, String userMessage) {
        if (builtInTools == null) {
            return FastLaneResult.fallbackToCore("RAG 检索工具不可用");
        }
        return callSimpleModelWithToolObject(
                route,
                userMessage,
                KNOWLEDGE_SYSTEM_PROMPT,
                new KnowledgeFastLaneTools(builtInTools)
        );
    }

    private FastLaneResult runMallTask(ShoppingIntentRoute route, String userMessage, String sessionId) {
        if (!SIMPLE_MALL_INTENTS.contains(route.normalizedIntent())) {
            return FastLaneResult.notHandled();
        }
        if (!StringUtils.hasText(sessionId)) {
            return FastLaneResult.fallbackToCore("简单商城任务缺少 sessionId");
        }
        if (mallMcpToolCallback == null) {
            return FastLaneResult.handled("商城 MCP 调用失败：mall-mcp client unavailable");
        }
        List<ToolCallback> callbacks;
        try {
            callbacks = simpleMallToolCallbacks();
        }
        catch (McpUnavailableException ex) {
            return FastLaneResult.handled("商城 MCP 调用失败：" + safeMessage(ex));
        }
        if (callbacks.isEmpty()) {
            return FastLaneResult.handled("商城 MCP 调用失败：未发现 mall_* MCP 工具");
        }
        return callSimpleModelWithToolCallbacks(
                route,
                userMessage,
                MALL_SYSTEM_PROMPT,
                callbacks,
                Map.of("sessionId", sessionId)
        );
    }

    private List<ToolCallback> simpleMallToolCallbacks() {
        try {
            return mallMcpToolCallback.getToolCallbacks().stream()
                    .filter(callback -> SIMPLE_MALL_TOOL_NAMES.contains(callback.getToolDefinition().name()))
                    .toList();
        }
        catch (RuntimeException ex) {
            throw new McpUnavailableException(safeMessage(ex));
        }
    }

    private FastLaneResult callSimpleModelWithToolObject(ShoppingIntentRoute route,
                                                         String userMessage,
                                                         String systemPrompt,
                                                         Object toolObject) {
        return callSimpleModel(route, () -> simpleTaskChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                        .model(modelName())
                        .temperature(0.0)
                        .maxTokens(Math.max(1, maxTokens))
                        .build())
                .system(systemPrompt)
                .user(buildUserPrompt(route, userMessage))
                .tools(toolObject)
                .call()
                .content());
    }

    private FastLaneResult callSimpleModelWithToolCallbacks(ShoppingIntentRoute route,
                                                            String userMessage,
                                                            String systemPrompt,
                                                            List<ToolCallback> toolCallbacks,
                                                            Map<String, Object> toolContext) {
        return callSimpleModel(route, () -> simpleTaskChatClient.prompt()
                .options(OpenAiChatOptions.builder()
                        .model(modelName())
                        .temperature(0.0)
                        .maxTokens(Math.max(1, maxTokens))
                        .build())
                .system(systemPrompt)
                .user(buildUserPrompt(route, userMessage))
                .toolCallbacks(toolCallbacks)
                .toolContext(toolContext)
                .call()
                .content());
    }

    private FastLaneResult callSimpleModel(ShoppingIntentRoute route, Supplier<String> modelCall) {
        try {
            log.info("简单任务小模型开始：taskType={}, intent={}, model={}",
                    route.normalizedTaskType(), route.normalizedIntent(), modelName());
            String content = modelCall.get();
            if (!StringUtils.hasText(content)) {
                return FastLaneResult.fallbackToCore("简单任务小模型返回空内容");
            }
            log.info("简单任务小模型完成：taskType={}, intent={}", route.normalizedTaskType(), route.normalizedIntent());
            return FastLaneResult.handled(content.trim());
        }
        catch (McpUnavailableException ex) {
            log.warn("简单商城任务 MCP 不可用：{}", ex.getMessage());
            return FastLaneResult.handled("商城 MCP 调用失败：" + safeMessage(ex));
        }
        catch (FastLaneFallbackException ex) {
            log.info("简单任务小模型降级主 Agent：{}", ex.getMessage());
            return FastLaneResult.fallbackToCore(ex.getMessage());
        }
        catch (RuntimeException ex) {
            if (isMcpUnavailable(ex)) {
                log.warn("简单商城任务 MCP 不可用：{}", ex.getMessage());
                return FastLaneResult.handled("商城 MCP 调用失败：" + safeMessage(ex));
            }
            log.warn("简单任务小模型失败，降级主 Agent：{}", ex.getMessage());
            log.debug("简单任务小模型异常堆栈", ex);
            return FastLaneResult.fallbackToCore("简单任务小模型失败：" + safeMessage(ex));
        }
    }

    private String buildUserPrompt(ShoppingIntentRoute route, String userMessage) {
        return """
                用户原话：
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
            String message = current.getMessage();
            if (StringUtils.hasText(message) && message.contains("mall-mcp 服务未启动或不可访问")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    static final class KnowledgeFastLaneTools {

        private final BuiltInTools builtInTools;

        KnowledgeFastLaneTools(BuiltInTools builtInTools) {
            this.builtInTools = builtInTools;
        }

        @Tool(description = "检索商品知识库中的商品特点、规格、适用年龄、评价摘要和导购知识。实时价格、库存、购物车和订单信息不能用这个工具回答。")
        public String searchProductKnowledge(
                @ToolParam(description = "用户想查询的商品知识、商品名称或问题", required = true) String query) {
            try {
                String result = builtInTools.searchProductKnowledge(query);
                if (!StringUtils.hasText(result) || result.contains(EMPTY_KNOWLEDGE_MESSAGE)) {
                    throw new FastLaneFallbackException("A 类 RAG 检索为空");
                }
                return result.trim();
            }
            catch (FastLaneFallbackException ex) {
                throw ex;
            }
            catch (RuntimeException ex) {
                throw new FastLaneFallbackException("A 类 RAG 检索失败：" + safeToolMessage(ex), ex);
            }
        }
    }

    static class FastLaneFallbackException extends RuntimeException {
        FastLaneFallbackException(String message) {
            super(message);
        }

        FastLaneFallbackException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class McpUnavailableException extends RuntimeException {
        McpUnavailableException(String message) {
            super(StringUtils.hasText(message) ? message : "mall-mcp 服务未启动或不可访问");
        }
    }

    private static String safeToolMessage(RuntimeException ex) {
        return ex == null || !StringUtils.hasText(ex.getMessage()) ? "unknown error" : ex.getMessage();
    }
}
