package com.example.ragagent.service;

import org.springframework.util.StringUtils;

import java.util.List;

enum MallTool {

    SEARCH_PRODUCTS(
            "mall_search_products",
            "搜索实时商城商品。适合按关键词、品类、品牌和价格区间查询商品、价格和库存。",
            """
                    {"type":"object","properties":{"keyword":{"type":"string"},"categoryId":{"type":"integer"},"brand":{"type":"string"},"minPrice":{"type":"number"},"maxPrice":{"type":"number"},"limit":{"type":"integer"},"sessionId":{"type":"string"}}}
                    """.trim()
    ),
    GET_PRODUCT_DETAIL(
            "mall_get_product_detail",
            "按真实商城 SKU ID 查询商品详情、价格、库存、规格、评价摘要和优惠信息。",
            """
                    {"type":"object","properties":{"skuId":{"type":"integer"},"sessionId":{"type":"string"}},"required":["skuId"]}
                    """.trim()
    ),
    ADD_TO_CART(
            "mall_add_to_cart",
            "把真实商城 SKU 加入当前用户购物车。服务端会重新查询商品详情并使用真实商品名和价格。",
            """
                    {"type":"object","properties":{"skuId":{"type":"integer"},"quantity":{"type":"integer","minimum":1},"sessionId":{"type":"string"}},"required":["skuId","quantity"]}
                    """.trim()
    ),
    VIEW_CART(
            "mall_view_cart",
            "查看当前用户购物车。",
            """
                    {"type":"object","properties":{"sessionId":{"type":"string"}}}
                    """.trim()
    ),
    PREPARE_ORDER(
            "mall_prepare_order",
            "确认当前用户购物车中的已选商品，返回订单摘要和短期有效 confirmationId。只确认，不创建订单。",
            """
                    {"type":"object","properties":{"sessionId":{"type":"string"}}}
                    """.trim()
    ),
    CREATE_ORDER(
            "mall_create_order",
            "用户明确二次确认后创建普通订单。必须传入 mall_prepare_order 返回的 confirmationId，并且 userConfirmed=true。",
            """
                    {"type":"object","properties":{"confirmationId":{"type":"string"},"userConfirmed":{"type":"boolean"},"remark":{"type":"string"},"sessionId":{"type":"string"}},"required":["confirmationId","userConfirmed"]}
                    """.trim()
    );

    private static final String MALL_TOOL_PREFIX = "mall_";
    private static final List<MallTool> ALL = List.of(values());

    private final String toolName;
    private final String description;
    private final String inputSchema;

    MallTool(String toolName, String description, String inputSchema) {
        this.toolName = toolName;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    static List<MallTool> all() {
        return ALL;
    }

    static boolean isMallTool(String toolName) {
        return StringUtils.hasText(toolName) && toolName.trim().startsWith(MALL_TOOL_PREFIX);
    }

    String toolName() {
        return toolName;
    }

    String description() {
        return description;
    }

    String inputSchema() {
        return inputSchema;
    }
}
