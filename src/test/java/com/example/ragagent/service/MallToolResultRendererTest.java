package com.example.ragagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MallToolResultRendererTest {

    private final MallToolResultRenderer renderer = new MallToolResultRenderer();

    @Test
    void shouldRenderSearchResultArrayAsChineseFacts() {
        String raw = """
                {"ok":true,"code":"OK","message":"success","data":[
                  {"skuId":2001,"spuId":101,"skuName":"轻量跑步鞋 42码","spuName":"轻量跑步鞋","brandName":"North Star","categoryName":"运动户外","price":399.00,"stock":300},
                  {"skuId":2002,"spuId":101,"skuName":"轻量跑步鞋 43码 白色","spuName":"轻量跑步鞋","brandName":"North Star","categoryName":"运动户外","price":399.00,"stock":260}
                ]}
                """;

        String result = renderer.render(MallTool.SEARCH_PRODUCTS.toolName(), raw);

        assertTrue(result.contains("工具结果事实：商城找到 2 个商品。"));
        assertTrue(result.contains("商品：轻量跑步鞋 42码"));
        assertTrue(result.contains("SKU：2001"));
        assertTrue(result.contains("品牌：North Star"));
        assertTrue(result.contains("价格：399.00 元"));
        assertTrue(result.contains("库存：300"));
        assertFalse(result.contains("{\"ok\""));
        assertFalse(result.contains("\"data\""));
    }

    @Test
    void shouldRenderEmptySearchResultAsNoMatch() {
        String raw = "{\"ok\":true,\"code\":\"OK\",\"message\":\"success\",\"data\":[]}";

        String result = renderer.render(MallTool.SEARCH_PRODUCTS.toolName(), raw);

        assertTrue(result.contains("商城没有找到匹配商品"));
    }

    @Test
    void shouldRenderProductDetailObjectWithNestedFacts() {
        String raw = """
                {"ok":true,"code":"OK","message":"success","data":{
                  "skuId":3023,
                  "spuId":112,
                  "skuName":"猫粮全价粮 10kg",
                  "spuName":"猫粮全价粮",
                  "categoryName":"宠物用品",
                  "brandName":"FreshPeak",
                  "price":329.00,
                  "stock":200,
                  "promotion":"Save 20 over 199",
                  "skuOptions":[
                    {"skuId":3022,"skuName":"猫粮全价粮 5kg","price":189.0},
                    {"skuId":3023,"skuName":"猫粮全价粮 10kg","price":329.0}
                  ],
                  "reviewSummary":{"averageRating":4.69,"reviewCount":181,"goodRate":95.8,"latestReview":"Large pack is better value."}
                }}
                """;

        String result = renderer.render(MallTool.GET_PRODUCT_DETAIL.toolName(), raw);

        assertTrue(result.contains("工具结果事实：商城返回商品详情。"));
        assertTrue(result.contains("商品：猫粮全价粮 10kg"));
        assertTrue(result.contains("促销：Save 20 over 199"));
        assertTrue(result.contains("评价：平均评分 4.69"));
        assertTrue(result.contains("最新评价：Large pack is better value."));
        assertTrue(result.contains("可选 SKU："));
        assertTrue(result.contains("商品：猫粮全价粮 5kg"));
        assertFalse(result.contains("{\"ok\""));
    }

    @Test
    void shouldRenderMcpTextContentWrapper() {
        String raw = "[{\"text\":\"{\\\"ok\\\":true,\\\"code\\\":\\\"OK\\\",\\\"data\\\":[{\\\"skuId\\\":2001,\\\"skuName\\\":\\\"轻量跑步鞋 42码\\\",\\\"price\\\":399.00,\\\"stock\\\":300}]}\"}]";

        String result = renderer.render(MallTool.SEARCH_PRODUCTS.toolName(), raw);

        assertTrue(result.contains("商城找到 1 个商品"));
        assertTrue(result.contains("商品：轻量跑步鞋 42码"));
        assertTrue(result.contains("价格：399.00 元"));
    }

    @Test
    void shouldRenderFailureAsNaturalLanguage() {
        String raw = "{\"ok\":false,\"code\":\"NOT_FOUND\",\"message\":\"sku missing\"}";

        String result = renderer.render(MallTool.GET_PRODUCT_DETAIL.toolName(), raw);

        assertTrue(result.contains("商城工具返回失败"));
        assertTrue(result.contains("错误码：NOT_FOUND"));
        assertTrue(result.contains("错误信息：sku missing"));
        assertFalse(result.contains("{\"ok\""));
    }
}
