package com.example.ragagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将商城 MCP 返回的 JSON 结构提炼为模型可直接引用的事实文本。
 */
final class MallToolResultRenderer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_ITEMS = 10;
    private static final Set<String> ENVELOPE_FIELDS = Set.of("ok", "code", "message", "data");
    private static final Set<String> INTERNAL_FIELDS = Set.of("sessionId");

    /**
     * 按工具类型渲染原始商城工具结果。
     */
    String render(String toolName, String rawResult) {
        if (!StringUtils.hasText(rawResult)) {
            return "工具结果事实：商城工具没有返回内容。";
        }
        JsonNode root = parseJson(rawResult);
        if (root == null) {
            return rawResult;
        }
        JsonNode normalized = unwrapMcpContent(root);
        if (normalized.isTextual()) {
            return normalized.asText();
        }
        if (normalized.path("ok").isBoolean() && !normalized.path("ok").asBoolean()) {
            return renderFailure(normalized);
        }
        JsonNode data = normalized.has("data") ? normalized.get("data") : normalized;
        return renderData(toolName, data, normalized.has("data"));
    }

    private JsonNode unwrapMcpContent(JsonNode root) {
        JsonNode content = root.has("content") ? root.get("content") : root;
        if (content.isArray() && !content.isEmpty() && content.get(0).has("text")) {
            return parseTextContent(content.get(0).path("text").asText());
        }
        if (root.has("structuredContent")) {
            return root.get("structuredContent");
        }
        return root;
    }

    private JsonNode parseTextContent(String text) {
        if (!StringUtils.hasText(text)) {
            return TextNode.valueOf("");
        }
        JsonNode parsed = parseJson(text);
        return parsed == null ? TextNode.valueOf(text) : parsed;
    }

    private String renderFailure(JsonNode root) {
        List<String> facts = new ArrayList<>();
        addTextFact(facts, "错误码", root, "code");
        addTextFact(facts, "错误原因", root, "reason");
        addTextFact(facts, "错误信息", root, "message");
        if (facts.isEmpty()) {
            return "工具结果事实：商城工具返回失败。";
        }
        return "工具结果事实：商城工具返回失败。\n- " + String.join("\n- ", facts);
    }

    private String renderData(String toolName, JsonNode data, boolean fromEnvelope) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return "工具结果事实：商城工具没有返回内容。";
        }
        if (data.isTextual()) {
            return data.asText();
        }
        if (data.isArray()) {
            return renderArray(toolName, data);
        }
        if (data.isObject()) {
            return renderObject(toolName, data, fromEnvelope ? Set.of() : ENVELOPE_FIELDS);
        }
        return "工具结果事实：商城工具返回：" + data.asText() + "。";
    }

    private String renderArray(String toolName, JsonNode array) {
        if (array.isEmpty()) {
            return emptyArrayMessage(toolName);
        }
        StringBuilder builder = new StringBuilder("工具结果事实：")
                .append(arrayHeader(toolName, array.size()));
        int limit = Math.min(array.size(), MAX_ITEMS);
        for (int i = 0; i < limit; i++) {
            builder.append("\n").append(i + 1).append(". ")
                    .append(itemSummary(array.get(i)));
        }
        if (array.size() > limit) {
            builder.append("\n仅列出前 ").append(limit).append(" 条。");
        }
        return builder.toString();
    }

    private String renderObject(String toolName, JsonNode object, Set<String> excludedFields) {
        StringBuilder builder = new StringBuilder(objectHeader(toolName));
        List<String> facts = objectFacts(object, excludedFields);
        if (!facts.isEmpty()) {
            for (String fact : facts) {
                builder.append("\n- ").append(fact);
            }
        }
        appendNestedArray(builder, object, "items", "商品明细");
        appendNestedArray(builder, object, "cartItems", "商品明细");
        appendNestedArray(builder, object, "orderItems", "订单商品");
        appendNestedArray(builder, object, "lines", "明细");
        appendNestedArray(builder, object, "products", "商品列表");
        appendNestedArray(builder, object, "skuOptions", "可选 SKU");
        appendNestedArray(builder, object, "coupons", "优惠券");
        return builder.toString();
    }

    private String itemSummary(JsonNode item) {
        if (item == null || item.isNull()) {
            return "空结果项";
        }
        if (!item.isObject()) {
            return item.asText();
        }
        List<String> facts = objectFacts(item, Set.of());
        if (facts.isEmpty()) {
            return "返回项包含结构化字段，但没有常用商品字段。";
        }
        return String.join("，", facts);
    }

    private List<String> objectFacts(JsonNode object, Set<String> excludedFields) {
        List<String> facts = new ArrayList<>();
        addFirstTextFact(facts, "商品", object, "skuName", "productName", "goodsName", "name");
        addTextFact(facts, "SPU", object, "spuName");
        addTextFact(facts, "SKU", object, "skuId");
        addTextFact(facts, "SPU ID", object, "spuId");
        addTextFact(facts, "品牌", object, "brandName", "brand");
        addTextFact(facts, "类目", object, "categoryName", "category");
        addTextFact(facts, "规格", object, "spec", "specName", "model", "size", "color", "capacity", "packageSpec");
        addMoneyFact(facts, "价格", object, "price", "salePrice");
        addMoneyFact(facts, "原价", object, "originalPrice", "listPrice");
        addNumberFact(facts, "库存", object, "stock", "availableStock", "salableStock", "inventory");
        addNumberFact(facts, "数量", object, "quantity", "count");
        addMoneyFact(facts, "小计", object, "subtotal");
        addMoneyFact(facts, "总价", object, "totalPrice", "totalAmount");
        addMoneyFact(facts, "应付金额", object, "payableAmount", "payAmount");
        addTextFact(facts, "促销", object, "promotion");
        addTextFact(facts, "确认 ID", object, "confirmationId");
        addTextFact(facts, "订单 ID", object, "orderId");
        addTextFact(facts, "状态", object, "status", "orderStatus");
        addTextFact(facts, "提示", object, "message");
        addReviewFact(facts, object.path("reviewSummary"));
        if (facts.isEmpty()) {
            facts.addAll(primitiveFacts(object, excludedFields));
        }
        return facts;
    }

    private void addReviewFact(List<String> facts, JsonNode review) {
        if (review == null || !review.isObject()) {
            return;
        }
        List<String> reviewFacts = new ArrayList<>();
        addInlineNumber(reviewFacts, "平均评分", review, "averageRating");
        addInlineNumber(reviewFacts, "评价数", review, "reviewCount");
        addInlineNumber(reviewFacts, "好评率", review, "goodRate");
        if (StringUtils.hasText(text(review.path("latestReview")))) {
            reviewFacts.add("最新评价：" + text(review.path("latestReview")));
        }
        if (!reviewFacts.isEmpty()) {
            facts.add("评价：" + String.join("，", reviewFacts));
        }
    }

    private List<String> primitiveFacts(JsonNode object, Set<String> excludedFields) {
        List<String> facts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext() && facts.size() < 12) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (excludedFields.contains(field.getKey()) || INTERNAL_FIELDS.contains(field.getKey())) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value == null || value.isNull() || value.isObject() || value.isArray()) {
                continue;
            }
            facts.add(labelOf(field.getKey()) + "：" + formatValue(field.getKey(), value));
        }
        return facts;
    }

    private void appendNestedArray(StringBuilder builder, JsonNode object, String field, String title) {
        JsonNode array = object.path(field);
        if (!array.isArray()) {
            return;
        }
        if (array.isEmpty()) {
            builder.append("\n- ").append(title).append("：无");
            return;
        }
        builder.append("\n").append(title).append("：");
        int limit = Math.min(array.size(), MAX_ITEMS);
        for (int i = 0; i < limit; i++) {
            builder.append("\n").append(i + 1).append(". ").append(itemSummary(array.get(i)));
        }
        if (array.size() > limit) {
            builder.append("\n仅列出前 ").append(limit).append(" 条。");
        }
    }

    private void addFirstTextFact(List<String> facts, String label, JsonNode object, String... fields) {
        String value = firstText(object, fields);
        if (StringUtils.hasText(value)) {
            facts.add(label + "：" + value);
        }
    }

    private void addTextFact(List<String> facts, String label, JsonNode object, String... fields) {
        String value = firstText(object, fields);
        if (StringUtils.hasText(value)) {
            facts.add(label + "：" + value);
        }
    }

    private void addMoneyFact(List<String> facts, String label, JsonNode object, String... fields) {
        JsonNode value = firstValue(object, fields);
        if (hasValue(value)) {
            facts.add(label + "：" + money(value) + " 元");
        }
    }

    private void addNumberFact(List<String> facts, String label, JsonNode object, String... fields) {
        JsonNode value = firstValue(object, fields);
        if (hasValue(value)) {
            facts.add(label + "：" + number(value));
        }
    }

    private void addInlineNumber(List<String> facts, String label, JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (hasValue(value)) {
            facts.add(label + " " + number(value));
        }
    }

    private JsonNode firstValue(JsonNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.path(field);
            if (hasValue(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode object, String... fields) {
        JsonNode value = firstValue(object, fields);
        return value == null ? "" : text(value);
    }

    private boolean hasValue(JsonNode value) {
        return value != null
                && !value.isMissingNode()
                && !value.isNull()
                && StringUtils.hasText(text(value));
    }

    private String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private String formatValue(String field, JsonNode value) {
        String lower = field.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("price") || lower.contains("amount")) {
            return money(value) + " 元";
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "是" : "否";
        }
        return text(value);
    }

    private String money(JsonNode value) {
        BigDecimal decimal = decimal(value);
        if (decimal == null) {
            return text(value);
        }
        return decimal.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String number(JsonNode value) {
        BigDecimal decimal = decimal(value);
        if (decimal == null) {
            return text(value);
        }
        BigDecimal normalized = decimal.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.UNNECESSARY);
        }
        return normalized.toPlainString();
    }

    private BigDecimal decimal(JsonNode value) {
        try {
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return new BigDecimal(value.asText().trim());
            }
        }
        catch (NumberFormatException ex) {
            return null;
        }
        return null;
    }

    private String labelOf(String field) {
        return switch (field) {
            case "skuId" -> "SKU";
            case "spuId" -> "SPU ID";
            case "skuName" -> "商品";
            case "spuName" -> "SPU";
            case "brandName", "brand" -> "品牌";
            case "categoryName", "category" -> "类目";
            case "price", "salePrice" -> "价格";
            case "stock", "availableStock", "salableStock", "inventory" -> "库存";
            case "quantity", "count" -> "数量";
            case "confirmationId" -> "确认 ID";
            case "orderId" -> "订单 ID";
            case "status", "orderStatus" -> "状态";
            default -> field;
        };
    }

    private String emptyArrayMessage(String toolName) {
        if (MallTool.SEARCH_PRODUCTS.toolName().equals(toolName)) {
            return "工具结果事实：商城没有找到匹配商品。";
        }
        if (MallTool.VIEW_CART.toolName().equals(toolName)) {
            return "工具结果事实：购物车为空。";
        }
        return "工具结果事实：商城工具返回空结果。";
    }

    private String arrayHeader(String toolName, int size) {
        if (MallTool.SEARCH_PRODUCTS.toolName().equals(toolName)) {
            return "商城找到 " + size + " 个商品。";
        }
        return "商城返回 " + size + " 条记录。";
    }

    private String objectHeader(String toolName) {
        if (MallTool.GET_PRODUCT_DETAIL.toolName().equals(toolName)) {
            return "工具结果事实：商城返回商品详情。";
        }
        if (MallTool.VIEW_CART.toolName().equals(toolName)) {
            return "工具结果事实：商城返回购物车信息。";
        }
        if (MallTool.PREPARE_ORDER.toolName().equals(toolName)) {
            return "工具结果事实：商城返回订单预览。";
        }
        if (MallTool.ADD_TO_CART.toolName().equals(toolName)) {
            return "工具结果事实：商城返回加购结果。";
        }
        if (MallTool.CREATE_ORDER.toolName().equals(toolName)) {
            return "工具结果事实：商城返回下单结果。";
        }
        return "工具结果事实：商城工具返回结果。";
    }

    private JsonNode parseJson(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        }
        catch (Exception ex) {
            return null;
        }
    }
}
