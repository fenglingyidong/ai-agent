package com.example.ragagent.service;

import com.example.ragagent.tools.BuiltInTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    private static final String KNOWLEDGE_SYSTEM_PROMPT = """
            你是电商导购的简单知识库问答助手。
            必须先调用 searchProductKnowledge 工具，再根据工具结果简短回答。
            不得编造工具结果之外的商品特点、价格、库存、优惠或订单信息。
            工具没有结果或工具失败时，不要自行猜测。
            输出纯文本中文回复，不要暴露工具名、JSON 或内部路由字段。
            """;

    private static final String MALL_SYSTEM_PROMPT = """
            你是电商导购的简单商城任务助手。
            必须使用提供的商城快车道工具完成任务，再根据工具结果简短回答。
            查价格、库存、属性和详情时调用 queryRealtimeProduct。
            明确加购物车时调用 addToCart；查看购物车时调用 viewCart；确认订单摘要时调用 prepareOrder。
            不允许创建订单、付款或编造 confirmationId；遇到确认下单应交给复杂任务。
            价格、库存、购物车和订单摘要只能来自工具结果。
            如果工具返回“商城 MCP 调用失败”，必须原样说明调用失败。
            输出纯文本中文回复，不要暴露工具名、JSON 或内部路由字段。
            """;

    private final ChatClient.Builder builder;
    private final BuiltInTools builtInTools;
    private final MallMcpOperations mallMcpOperations;

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
                           MallMcpOperations mallMcpOperations) {
        this.builder = builder;
        this.builtInTools = builtInTools;
        this.mallMcpOperations = mallMcpOperations;
    }

    SimpleTaskAgent(ChatClient simpleTaskChatClient,
                    BuiltInTools builtInTools,
                    MallMcpOperations mallMcpOperations) {
        this.builder = null;
        this.simpleTaskChatClient = simpleTaskChatClient;
        this.builtInTools = builtInTools;
        this.mallMcpOperations = mallMcpOperations;
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
        return callSimpleModel(
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
        if (mallMcpOperations == null) {
            return FastLaneResult.handled("商城 MCP 调用失败：mall-mcp client unavailable");
        }
        return callSimpleModel(
                route,
                userMessage,
                MALL_SYSTEM_PROMPT,
                new MallFastLaneTools(mallMcpOperations, sessionId, userMessage)
        );
    }

    private FastLaneResult callSimpleModel(ShoppingIntentRoute route,
                                           String userMessage,
                                           String systemPrompt,
                                           Object toolObject) {
        try {
            log.info("简单任务小模型开始：taskType={}, intent={}, model={}",
                    route.normalizedTaskType(), route.normalizedIntent(), modelName());
            String content = simpleTaskChatClient.prompt()
                    .options(OpenAiChatOptions.builder()
                            .model(modelName())
                            .temperature(0.0)
                            .maxTokens(Math.max(1, maxTokens))
                            .build())
                    .system(systemPrompt)
                    .user(buildUserPrompt(route, userMessage))
                    .tools(toolObject)
                    .call()
                    .content();
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

    static final class MallFastLaneTools {

        private final MallMcpOperations mallMcpOperations;
        private final String sessionId;
        private final String userMessage;

        MallFastLaneTools(MallMcpOperations mallMcpOperations, String sessionId, String userMessage) {
            this.mallMcpOperations = mallMcpOperations;
            this.sessionId = sessionId;
            this.userMessage = userMessage;
        }

        @Tool(description = "查询真实商城商品详情、实时价格、库存、属性和促销摘要。skuId 明确时优先传 skuId，否则传商品名。")
        public String queryRealtimeProduct(
                @ToolParam(description = "商品名称或关键词", required = false) String productName,
                @ToolParam(description = "真实商城 SKU ID", required = false) Long skuId) {
            Long resolvedSkuId = resolveSkuId(productName, skuId);
            ObjectNode args = argsWithSession();
            args.put("skuId", resolvedSkuId);
            return call(MallTool.GET_PRODUCT_DETAIL, args);
        }

        @Tool(description = "把明确商品加入当前用户购物车。必须有商品名称或 skuId，并且 quantity 大于 0。")
        public String addToCart(
                @ToolParam(description = "商品名称或关键词", required = false) String productName,
                @ToolParam(description = "真实商城 SKU ID", required = false) Long skuId,
                @ToolParam(description = "加入购物车数量", required = true) Integer quantity) {
            if (quantity == null || quantity < 1) {
                throw new FastLaneFallbackException("加购物车缺少有效数量");
            }
            Long resolvedSkuId = resolveSkuId(productName, skuId);
            ObjectNode args = argsWithSession();
            args.put("skuId", resolvedSkuId);
            args.put("quantity", quantity);
            return call(MallTool.ADD_TO_CART, args);
        }

        @Tool(description = "查看当前用户购物车。")
        public String viewCart() {
            return call(MallTool.VIEW_CART, argsWithSession());
        }

        @Tool(description = "确认当前购物车订单摘要，返回待用户核对的订单确认信息和 confirmationId，但不创建订单。")
        public String prepareOrder() {
            return call(MallTool.PREPARE_ORDER, argsWithSession());
        }

        private Long resolveSkuId(String productName, Long skuId) {
            if (skuId != null && skuId > 0) {
                return skuId;
            }
            if (!StringUtils.hasText(productName)) {
                throw new FastLaneFallbackException("简单商城任务缺少商品名称或 SKU");
            }
            ObjectNode args = argsWithSession();
            args.put("keyword", productName.trim());
            args.put("limit", 5);
            MallMcpOperations.MallMcpCallResult result = mallMcpOperations.callTool(MallTool.SEARCH_PRODUCTS.toolName(), args);
            if (result.serviceUnavailable()) {
                throw new McpUnavailableException(result.message());
            }
            if (!result.ok()) {
                return nullFromFailedSearch(result);
            }
            JsonNode data = result.envelope().path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new FastLaneFallbackException("没有查到匹配商品");
            }
            JsonNode selected = selectExactOrUnique(productName, data);
            if (selected == null || !selected.path("skuId").canConvertToLong()) {
                throw new FastLaneFallbackException("找到多个可能商品，需要主 Agent 追问确认");
            }
            return selected.path("skuId").asLong();
        }

        private Long nullFromFailedSearch(MallMcpOperations.MallMcpCallResult result) {
            throw new FastLaneFallbackException("商城商品搜索失败：" + result.message());
        }

        private String call(MallTool tool, ObjectNode args) {
            MallMcpOperations.MallMcpCallResult result = mallMcpOperations.callTool(tool.toolName(), args);
            if (result.serviceUnavailable()) {
                throw new McpUnavailableException(result.message());
            }
            return result.rawResult();
        }

        private ObjectNode argsWithSession() {
            return mallMcpOperations.argsWithSession(sessionId);
        }

        private JsonNode selectExactOrUnique(String productName, JsonNode data) {
            if (data.size() == 1) {
                return data.get(0);
            }
            String normalizedProductName = compact(productName);
            String normalizedUserMessage = compact(userMessage);
            List<JsonNode> exactMatches = new ArrayList<>();
            data.forEach(item -> {
                String itemName = firstText(
                        item.path("skuName").asText(""),
                        item.path("spuName").asText("")
                );
                String normalizedItemName = compact(itemName);
                if (StringUtils.hasText(normalizedItemName)
                        && (normalizedProductName.equals(normalizedItemName) || normalizedUserMessage.contains(normalizedItemName))) {
                    exactMatches.add(item);
                }
            });
            return exactMatches.size() == 1 ? exactMatches.get(0) : null;
        }

        private String compact(String value) {
            return StringUtils.hasText(value) ? value.replaceAll("\\s+", "").trim() : "";
        }

        private String firstText(String... values) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
            return "";
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
