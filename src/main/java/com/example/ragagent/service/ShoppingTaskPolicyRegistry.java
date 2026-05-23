package com.example.ragagent.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ShoppingTaskPolicyRegistry {

    static final String PRODUCT_SELECTION = "PRODUCT_SELECTION";
    static final String PRODUCT_COMPARE = "PRODUCT_COMPARE";
    static final String FOLLOW_UP = "FOLLOW_UP";
    static final String RECOMMENDATION = "RECOMMENDATION";
    static final String CART_CONFIRMATION = "CART_CONFIRMATION";

    private final Map<String, ShoppingTaskPolicy> policiesById;

    public ShoppingTaskPolicyRegistry() {
        LinkedHashMap<String, ShoppingTaskPolicy> policies = new LinkedHashMap<>();
        register(policies, new ShoppingTaskPolicy(
                PRODUCT_SELECTION,
                "选品",
                Set.of("QUERY_ATTRIBUTE", "PRICE_STOCK_QUERY", "FIND_SIMILAR", "COMPLEX_RECOMMENDATION"),
                Set.of("category", "product_name"),
                Set.of("searchProductKnowledge", "updateShoppingPreference",
                        "mall_search_products", "mall_get_product_detail"),
                false,
                "选品阶段：先确认品类、预算、品牌、颜色、尺码、使用场景等约束；需要实时价格、库存或具体商品详情时使用 mall_* 工具。"
        ));
        register(policies, new ShoppingTaskPolicy(
                PRODUCT_COMPARE,
                "对比",
                Set.of("COMPLEX_RECOMMENDATION"),
                Set.of("candidates"),
                Set.of("searchProductKnowledge", "mall_search_products", "mall_get_product_detail"),
                false,
                "对比阶段：围绕用户关心的维度比较候选商品，说明适用场景、关键差异、风险点和推荐结论。"
        ));
        register(policies, new ShoppingTaskPolicy(
                FOLLOW_UP,
                "追问补槽",
                Set.of("UNKNOWN", "COMPLEX_RECOMMENDATION", "ADD_TO_CART", "PRICE_STOCK_QUERY"),
                Set.of(),
                Set.of("updateShoppingPreference"),
                false,
                "追问补槽阶段：先追问缺失参数，一次最多问 2 个关键问题；不要在关键槽位缺失时直接调用高风险工具。"
        ));
        register(policies, new ShoppingTaskPolicy(
                RECOMMENDATION,
                "推荐",
                Set.of("COMPLEX_RECOMMENDATION"),
                Set.of("budget", "use_scene"),
                Set.of("searchProductKnowledge", "updateShoppingPreference",
                        "mall_search_products", "mall_get_product_detail"),
                false,
                "推荐阶段：结合用户偏好、预算和场景给出少量高质量候选，并解释推荐理由和可替代选择。"
        ));
        register(policies, new ShoppingTaskPolicy(
                CART_CONFIRMATION,
                "加购确认",
                Set.of("ADD_TO_CART", "PREPARE_ORDER", "CREATE_ORDER"),
                Set.of("skuId", "quantity"),
                Set.of("mall_get_product_detail", "mall_add_to_cart", "mall_view_cart",
                        "mall_prepare_order", "mall_create_order"),
                true,
                "加购确认阶段：涉及购物车、订单预览或下单前必须复述商品、规格、数量、价格和收货摘要，并等待用户确认后再继续。"
        ));
        this.policiesById = Collections.unmodifiableMap(policies);
    }

    public List<ShoppingTaskPolicy> resolve(ShoppingIntentRoute route) {
        if (route == null) {
            return List.of();
        }

        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        if (route.hasMissingSlots()) {
            selectedIds.add(FOLLOW_UP);
        }

        boolean hasExplicitPolicy = addExplicitPolicies(selectedIds, route.taskPolicies());
        if (!hasExplicitPolicy) {
            addDefaultPolicies(selectedIds, route.normalizedIntent());
        }

        if (requiresCartConfirmation(route)) {
            selectedIds.add(CART_CONFIRMATION);
        }

        List<ShoppingTaskPolicy> resolved = new ArrayList<>();
        for (String policyId : selectedIds) {
            ShoppingTaskPolicy policy = policiesById.get(policyId);
            if (policy != null) {
                resolved.add(policy);
            }
        }
        return List.copyOf(resolved);
    }

    private void register(Map<String, ShoppingTaskPolicy> policies, ShoppingTaskPolicy policy) {
        policies.put(policy.id(), policy);
    }

    private boolean addExplicitPolicies(LinkedHashSet<String> selectedIds, List<String> policyIds) {
        boolean added = false;
        for (String policyId : policyIds) {
            String normalized = normalizePolicyId(policyId);
            if (policiesById.containsKey(normalized)) {
                selectedIds.add(normalized);
                added = true;
            }
        }
        return added;
    }

    private void addDefaultPolicies(LinkedHashSet<String> selectedIds, String intent) {
        switch (intent) {
            case "QUERY_ATTRIBUTE", "PRICE_STOCK_QUERY", "FIND_SIMILAR" -> selectedIds.add(PRODUCT_SELECTION);
            case "COMPLEX_RECOMMENDATION" -> {
                selectedIds.add(PRODUCT_SELECTION);
                selectedIds.add(RECOMMENDATION);
            }
            case "ADD_TO_CART", "PREPARE_ORDER", "CREATE_ORDER" -> selectedIds.add(CART_CONFIRMATION);
            default -> {
            }
        }
    }

    private boolean requiresCartConfirmation(ShoppingIntentRoute route) {
        String intent = route.normalizedIntent();
        return Boolean.TRUE.equals(route.needConfirm())
                || "ADD_TO_CART".equals(intent)
                || "PREPARE_ORDER".equals(intent)
                || "CREATE_ORDER".equals(intent);
    }

    private String normalizePolicyId(String policyId) {
        if (!StringUtils.hasText(policyId)) {
            return "";
        }
        return policyId.trim().toUpperCase(Locale.ROOT);
    }
}
