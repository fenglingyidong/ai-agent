package com.example.ragagent.tools;

import com.example.ragagent.commerce.ShoppingPreferenceState;
import com.example.ragagent.commerce.ShoppingPreferenceSource;
import com.example.ragagent.commerce.ShoppingStateService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

@Component
public class BuiltInTools {

    private static final String TOOL_CONTEXT_USER_ID = "userId";
    private static final String TOOL_CONTEXT_SESSION_ID = "sessionId";
    public static final String TOOL_CONTEXT_SEARCH_PRODUCT_KNOWLEDGE_CACHE = "searchProductKnowledgeCache";
    private static final String DEFAULT_USER_ID = "anonymous";
    private static final String DEFAULT_SESSION_ID = "default";

    @Autowired
    private DocumentRetriever documentRetriever;

    @Autowired
    private ShoppingStateService shoppingStateService;

    public BuiltInTools() {
    }

    public BuiltInTools(DocumentRetriever documentRetriever, ShoppingStateService shoppingStateService) {
        this.documentRetriever = documentRetriever;
        this.shoppingStateService = shoppingStateService;
    }

    public String searchProductKnowledge(String query) {
        return searchProductKnowledge(query, null);
    }

    @Tool(description = "检索商品知识库、商品详情、规格参数、评价摘要和导购话术。适合回答已入库的商品事实、选品建议和对比依据；实时价格、库存、购物车和订单必须使用 mall_* MCP 工具。")
    public String searchProductKnowledge(@ToolParam(description = "用户商品需求或需要核验的商品事实") String query,
                                         ToolContext toolContext) {
        String normalizedQuery = normalizeQuery(query);
        ConcurrentMap<String, String> cache = searchProductKnowledgeCache(toolContext);
        if (cache == null) {
            return renderProductKnowledge(normalizedQuery);
        }
        return cache.computeIfAbsent(normalizedQuery, this::renderProductKnowledge);
    }

    private String renderProductKnowledge(String query) {
        List<Document> documents = documentRetriever.retrieve(new Query(query));
        if (documents == null || documents.isEmpty()) {
            return "商品知识库中没有检索到相关内容。";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            if (index > 0) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }

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
        return builder.toString();
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

    @Tool(description = "更新用户短期导购偏好状态，包括品类、预算、品牌、尺码、颜色、风格和使用场景。不要把 token、密码、手机号等敏感信息写入偏好状态。")
    public String updateShoppingPreference(String category,
                                           Integer budgetMin,
                                           Integer budgetMax,
                                           String brand,
                                           String size,
                                           String color,
                                           String style,
                                           String usageScenario,
                                           ToolContext toolContext) {
        ShoppingPreferenceState state = shoppingStateService.mergePreference(
                resolveCurrentUserId(toolContext),
                resolveCurrentSessionId(toolContext),
                new ShoppingStateService.ShoppingPreferencePatch(
                        category,
                        budgetMin,
                        budgetMax,
                        brand,
                        size,
                        color,
                        style,
                        usageScenario,
                        Set.of(),
                        ShoppingPreferenceSource.MODEL_TOOL.name(),
                        1.0,
                        null
                )
        );
        return renderPreference(state);
    }

    private String renderPreference(ShoppingPreferenceState state) {
        return """
                已更新导购偏好：
                品类：%s
                预算：%s-%s
                品牌：%s
                尺码：%s
                颜色：%s
                风格：%s
                使用场景：%s
                """.formatted(
                emptyToUnset(state.getCategory()),
                state.getBudgetMin() == null ? "未设置" : state.getBudgetMin(),
                state.getBudgetMax() == null ? "未设置" : state.getBudgetMax(),
                emptyToUnset(state.getBrand()),
                emptyToUnset(state.getSize()),
                emptyToUnset(state.getColor()),
                emptyToUnset(state.getStyle()),
                emptyToUnset(state.getUsageScenario())
        ).trim();
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

    private String resolveCurrentUserId(ToolContext toolContext) {
        String contextUserId = readToolContextValue(toolContext, TOOL_CONTEXT_USER_ID);
        if (StringUtils.hasText(contextUserId)) {
            return contextUserId;
        }
        return DEFAULT_USER_ID;
    }

    private String resolveCurrentSessionId(ToolContext toolContext) {
        String contextSessionId = readToolContextValue(toolContext, TOOL_CONTEXT_SESSION_ID);
        if (StringUtils.hasText(contextSessionId)) {
            return contextSessionId;
        }
        return DEFAULT_SESSION_ID;
    }

    private String readToolContextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null || !StringUtils.hasText(key)) {
            return "";
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String emptyToUnset(String value) {
        return StringUtils.hasText(value) ? value : "未设置";
    }
}
