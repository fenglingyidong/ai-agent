package com.example.ragagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingTaskPolicyRegistryTest {

    private final ShoppingTaskPolicyRegistry registry = new ShoppingTaskPolicyRegistry();

    @Test
    void shouldResolvePlannerSelectedPoliciesInOrder() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "COMPLEX_REACT",
                Map.of(),
                Map.of("budget", "300"),
                true,
                0.9,
                "复杂推荐",
                List.of("PRODUCT_SELECTION", "FOLLOW_UP", "RECOMMENDATION"),
                List.of(),
                List.of("searchProductKnowledge"),
                false,
                "MEDIUM"
        );

        List<ShoppingTaskPolicy> policies = registry.resolve(route);

        assertEquals(List.of("PRODUCT_SELECTION", "FOLLOW_UP", "RECOMMENDATION"),
                policies.stream().map(ShoppingTaskPolicy::id).toList());
        assertTrue(policies.get(1).promptFragment().contains("先追问缺失参数"));
    }

    @Test
    void shouldFallbackToCartConfirmationForCreateOrder() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CREATE_ORDER",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.95,
                "最终下单"
        );

        List<ShoppingTaskPolicy> policies = registry.resolve(route);

        assertEquals(List.of("CART_CONFIRMATION"), policies.stream().map(ShoppingTaskPolicy::id).toList());
        assertTrue(policies.get(0).confirmationRequired());
        assertTrue(policies.get(0).allowedToolNames().contains("mall_create_order"));
    }

    @Test
    void shouldPrependFollowUpWhenRouteHasMissingSlots() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRICE_STOCK_QUERY",
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

        assertEquals(List.of("FOLLOW_UP", "PRODUCT_SELECTION"),
                policies.stream().map(ShoppingTaskPolicy::id).toList());
    }
}
