package com.example.ragagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingTaskPolicyRegistryTest {

    private final ShoppingTaskPolicyRegistry registry = new ShoppingTaskPolicyRegistry();

    @Test
    void shouldResolveComplexRecommendationSkillsFromIntent() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "RECOMMENDATION",
                "COMPLEX_REACT",
                Map.of(),
                Map.of("budget", "300"),
                true,
                0.9,
                "复杂推荐",
                List.of("IGNORED_BY_REGISTRY"),
                List.of(),
                List.of("searchProductKnowledge"),
                false,
                "MEDIUM"
        );

        List<ShoppingTaskPolicy> policies = registry.resolve(route);

        assertEquals(List.of("RECOMMENDATION"),
                policies.stream().map(ShoppingTaskPolicy::id).toList());
        assertEquals("推荐", policies.get(0).name());
        assertTrue(policies.get(0).promptFragment().contains("推荐任务"));
        assertTrue(policies.get(0).promptFragment().contains("先调用 searchProductKnowledge"));
        assertTrue(policies.get(0).promptFragment().contains("不得请求用户先补充信息"));
    }

    @Test
    void shouldResolveProductCompareSkillFromIntent() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_COMPARE",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.9,
                "商品对比"
        );

        List<ShoppingTaskPolicy> policies = registry.resolve(route);

        assertEquals(List.of("PRODUCT_COMPARE"),
                policies.stream().map(ShoppingTaskPolicy::id).toList());
        assertTrue(policies.get(0).promptFragment().contains("对比任务"));
    }

    @Test
    void shouldKeepOrderCreationOutOfCartConfirmationPolicy() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CART_CONFIRMATION",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.95,
                "最终下单",
                List.of(),
                List.of(),
                List.of("mall_create_order"),
                true,
                "HIGH"
        );

        List<ShoppingTaskPolicy> policies = registry.resolve(route);

        assertEquals(List.of("CART_CONFIRMATION"), policies.stream().map(ShoppingTaskPolicy::id).toList());
        assertTrue(policies.get(0).confirmationRequired());
        assertTrue(policies.get(0).allowedToolNames().contains("mall_prepare_order"));
        assertTrue(policies.get(0).allowedToolNames().stream().noneMatch("mall_create_order"::equals));
        assertTrue(policies.get(0).promptFragment().contains("最终下单不由大模型执行"));
        assertTrue(policies.get(0).promptFragment().contains("前端确认卡片"));
    }

    @Test
    void shouldNotAddFollowUpWhenRouteHasMissingSlots() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木"),
                true,
                0.86,
                "缺少 SKU",
                List.of("PRODUCT_SELECTION"),
                List.of("skuId"),
                List.of("mall_search_products"),
                false,
                "LOW"
        );

        List<ShoppingTaskPolicy> policies = registry.resolve(route);

        assertEquals(List.of("PRODUCT_SELECTION"),
                policies.stream().map(ShoppingTaskPolicy::id).toList());
    }

    @Test
    void shouldResolveEmptyUnknownSkillWithoutFallbackPolicies() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "UNKNOWN",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.8,
                "未知复杂任务"
        );

        List<ShoppingTaskPolicy> policies = registry.resolve(route);

        assertEquals(List.of("UNKNOWN"), policies.stream().map(ShoppingTaskPolicy::id).toList());
        assertEquals("前置路由未能识别用户意图，请自行判断。", policies.get(0).promptFragment());
        assertTrue(policies.get(0).allowedToolNames().isEmpty());
    }
}
