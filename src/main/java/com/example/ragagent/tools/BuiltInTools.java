package com.example.ragagent.tools;

import com.example.ragagent.memory.ConversationToolCallMemoryService;
import com.example.ragagent.observability.RagTracing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
 * 注册给 ReAct agent 的内置工具，当前负责商品知识库检索和商城实时详情补强。
 */
@Component
public class BuiltInTools {

    public static final String TOOL_CONTEXT_SEARCH_PRODUCT_KNOWLEDGE_CACHE = "searchProductKnowledgeCache";
    private static final int MAX_MALL_DETAIL_SKUS = 3;

    @Autowired
    private DocumentRetriever documentRetriever;

    @Autowired(required = false)
    private List<ToolCallbackProvider> toolCallbackProviders = List.of();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagTracing tracing;

    @Autowired(required = false)
    private ConversationToolCallMemoryService toolCallMemoryService;

    public BuiltInTools() {
    }

    public BuiltInTools(DocumentRetriever documentRetriever) {
        this(documentRetriever, List.of(), new ObjectMapper());
    }

    public BuiltInTools(DocumentRetriever documentRetriever,
                        List<ToolCallbackProvider> toolCallbackProviders,
                        ObjectMapper objectMapper) {
        this(documentRetriever, toolCallbackProviders, objectMapper, null);
    }

    public BuiltInTools(DocumentRetriever documentRetriever,
                        List<ToolCallbackProvider> toolCallbackProviders,
                        ObjectMapper objectMapper,
                        ConversationToolCallMemoryService toolCallMemoryService) {
        this.documentRetriever = documentRetriever;
        this.toolCallbackProviders = toolCallbackProviders == null ? List.of() : List.copyOf(toolCallbackProviders);
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.tracing = new RagTracing();
        this.toolCallMemoryService = toolCallMemoryService;
    }

    /**
     * 便捷检索商品知识库，供测试或无工具上下文的调用方使用。
     */
    public String searchProductKnowledge(String query) {
        return searchProductKnowledge(query, null);
    }

    /**
     * 检索商品知识库，并在可用时根据检索到的 SKU 调用商城 MCP 获取实时详情。
     */
    @Tool(description = "检索商品知识库、商品详情、规格参数、评价摘要和导购话术。适合回答已入库的商品事实、选品建议和对比依据；返回内容可能包含商城实时详情补强，若未包含，实时价格、库存、购物车和订单必须使用 mall_* MCP 工具。")
    public String searchProductKnowledge(@ToolParam(description = "用户商品需求或需要核验的商品事实") String query,
                                         ToolContext toolContext) {
        String normalizedQuery = normalizeQuery(query);
        ConcurrentMap<String, String> cache = searchProductKnowledgeCache(toolContext);
        if (cache == null) {
            return renderProductKnowledge(normalizedQuery, toolContext);
        }
        return cache.computeIfAbsent(normalizedQuery, key -> renderProductKnowledge(key, toolContext));
    }

    private String renderProductKnowledge(String query, ToolContext toolContext) {
        List<Document> documents = documentRetriever.retrieve(new Query(query));
        if (documents == null || documents.isEmpty()) {
            return "商品知识库中没有检索到相关内容。";
        }

        StringBuilder builder = new StringBuilder("""
                回答约束：以下内容来自当前商品知识库原文和元数据；商品名、SKU、品牌、类目、规格、适用场景、评价摘要、价格桶和导购说明可作为商品知识库事实。价格桶只用于预算召回和初筛，不是精确价格。精确价格、库存、可售数和促销优惠必须以后续商城实时详情或 mall_* 工具结果为准。不要引用或保留已废弃的历史快照口径。
                """.trim());
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            builder.append(System.lineSeparator()).append(System.lineSeparator());

            builder.append("[商品知识 ").append(index + 1).append("]");
            appendMetadata(builder, document, "title", "标题");
            appendMetadata(builder, document, "productId", "商品ID");
            appendMetadata(builder, document, "skuId", "SKU");
            appendMetadata(builder, document, "brand", "品牌");
            appendMetadata(builder, document, "category", "类目");
            appendMetadata(builder, document, "sourceId", "来源");
            appendMetadata(builder, document, "attributes", "属性");
            builder.append(System.lineSeparator()).append(document.getText());
        }
        appendMallRealtimeDetails(builder, documents, toolContext);
        return builder.toString();
    }

    private void appendMallRealtimeDetails(StringBuilder builder, List<Document> documents, ToolContext toolContext) {
        List<String> skuIds = uniqueSkuIds(documents);
        ToolCallback mallDetailCallback = mallProductDetailCallback();
        if (skuIds.isEmpty() || mallDetailCallback == null) {
            return;
        }

        RagTracing activeTracing = tracing == null ? new RagTracing() : tracing;
        activeTracing.inSpan("rag.mall_enrich", () -> {
            Span span = activeTracing.currentSpan();
            activeTracing.setAttribute(span, "rag.mall_enrich.sku_count", skuIds.size());
            int successCount = 0;
            int failedCount = 0;
            builder.append(System.lineSeparator()).append(System.lineSeparator());
            builder.append("商城实时详情补强：以下内容来自 mall_get_product_detail；价格、库存、可售数和促销优惠等实时字段以本段为准。商品知识库只提供价格桶和非实时商品知识，不提供实时交易字段。");
            for (int index = 0; index < skuIds.size(); index++) {
                String skuId = skuIds.get(index);
                builder.append(System.lineSeparator()).append(System.lineSeparator());
                builder.append("[商城实时详情 ").append(index + 1).append("]");
                builder.append(System.lineSeparator()).append("SKU: ").append(skuId);
                String input = "";
                try {
                    input = mallDetailArguments(skuId).toString();
                    String detail = mallDetailCallback.call(input, toolContext);
                    rememberMallDetailSuccess(toolContext, input, detail);
                    builder.append(System.lineSeparator()).append("查询状态: 成功");
                    builder.append(System.lineSeparator()).append(detail);
                    successCount++;
                }
                catch (RuntimeException ex) {
                    rememberMallDetailError(toolContext, input, ex);
                    builder.append(System.lineSeparator()).append("查询状态: 失败");
                    builder.append(System.lineSeparator()).append("说明: 保留上方商品知识库结果；商城实时详情暂不可用。");
                    builder.append(System.lineSeparator()).append("错误类型: ").append(ex.getClass().getSimpleName());
                    failedCount++;
                }
            }
            activeTracing.setAttribute(span, "rag.mall_enrich.success_count", successCount);
            activeTracing.setAttribute(span, "rag.mall_enrich.failed_count", failedCount);
            activeTracing.setAttribute(span, "rag.mall_enrich.sku_ids", String.join(",", skuIds));
            return null;
        });
    }

    private void rememberMallDetailSuccess(ToolContext toolContext, String input, String detail) {
        if (toolCallMemoryService == null) {
            return;
        }
        try {
            toolCallMemoryService.rememberSuccess(
                    contextValue(toolContext, "userId"),
                    contextValue(toolContext, "sessionId"),
                    "mall_get_product_detail",
                    input,
                    detail
            );
        }
        catch (RuntimeException ignored) {
        }
    }

    private void rememberMallDetailError(ToolContext toolContext, String input, RuntimeException ex) {
        if (toolCallMemoryService == null) {
            return;
        }
        try {
            toolCallMemoryService.rememberError(
                    contextValue(toolContext, "userId"),
                    contextValue(toolContext, "sessionId"),
                    "mall_get_product_detail",
                    input,
                    ex
            );
        }
        catch (RuntimeException ignored) {
        }
    }

    private String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return "";
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? "" : value.toString();
    }

    private ToolCallback mallProductDetailCallback() {
        if (toolCallbackProviders == null || toolCallbackProviders.isEmpty()) {
            return null;
        }
        for (ToolCallbackProvider provider : toolCallbackProviders) {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            if (callbacks == null) {
                continue;
            }
            for (ToolCallback callback : callbacks) {
                if (callback != null
                        && callback.getToolDefinition() != null
                        && "mall_get_product_detail".equals(callback.getToolDefinition().name())) {
                    return callback;
                }
            }
        }
        return null;
    }

    private List<String> uniqueSkuIds(List<Document> documents) {
        LinkedHashSet<String> skuIds = new LinkedHashSet<>();
        if (documents == null) {
            return List.of();
        }
        for (Document document : documents) {
            String skuId = readSkuId(document);
            if (StringUtils.hasText(skuId)) {
                skuIds.add(skuId.trim());
            }
            if (skuIds.size() >= MAX_MALL_DETAIL_SKUS) {
                break;
            }
        }
        return List.copyOf(skuIds);
    }

    private String readSkuId(Document document) {
        String directSkuId = readMetadataValue(document, "skuId");
        if (StringUtils.hasText(directSkuId)) {
            return directSkuId;
        }
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        Object attributes = document.getMetadata().get("attributes");
        if (attributes instanceof java.util.Map<?, ?> map) {
            Object value = map.get("skuId");
            return value == null ? "" : value.toString();
        }
        return "";
    }

    private ObjectNode mallDetailArguments(String skuId) {
        ObjectNode arguments = objectMapper == null ? new ObjectMapper().createObjectNode() : objectMapper.createObjectNode();
        long numericSkuId = Long.parseLong(skuId.trim());
        arguments.put("skuId", numericSkuId);
        return arguments;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, String> searchProductKnowledgeCache(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(TOOL_CONTEXT_SEARCH_PRODUCT_KNOWLEDGE_CACHE);
        if (value instanceof ConcurrentMap<?, ?> cache) {
            return (ConcurrentMap<String, String>) cache;
        }
        return null;
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().replace("\r\n", "\n").replace('\r', '\n');
    }

    private void appendMetadata(StringBuilder builder, Document document, String key, String label) {
        String value = readMetadataValue(document, key);
        if (StringUtils.hasText(value)) {
            builder.append(System.lineSeparator()).append(label).append(": ").append(value);
        }
    }

    private String readMetadataValue(Document document, String key) {
        if (document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

}
