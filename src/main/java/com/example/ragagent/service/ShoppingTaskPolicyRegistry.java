package com.example.ragagent.service;

import com.example.ragagent.prompt.PromptTemplateStore;
import org.springframework.beans.factory.annotation.Autowired;
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
    static final String RECOMMENDATION = "RECOMMENDATION";
    static final String CART_CONFIRMATION = "CART_CONFIRMATION";
    static final String UNKNOWN = "UNKNOWN";

    private final Map<String, ShoppingTaskPolicy> policiesById;
    private final PromptTemplateStore promptTemplateStore;

    public ShoppingTaskPolicyRegistry() {
        this(new PromptTemplateStore());
    }

    @Autowired
    public ShoppingTaskPolicyRegistry(PromptTemplateStore promptTemplateStore) {
        this.promptTemplateStore = promptTemplateStore == null ? new PromptTemplateStore() : promptTemplateStore;
        LinkedHashMap<String, ShoppingTaskPolicy> policies = new LinkedHashMap<>();
        register(policies, new ShoppingTaskPolicy(
                PRODUCT_SELECTION,
                "选品",
                Set.of(PRODUCT_SELECTION),
                Set.of("category", "product_name"),
                Set.of("searchProductKnowledge", "mall_search_products", "mall_get_product_detail"),
                false,
                skill("product-selection")
        ));
        register(policies, new ShoppingTaskPolicy(
                PRODUCT_COMPARE,
                "对比",
                Set.of(PRODUCT_COMPARE),
                Set.of("candidates"),
                Set.of("searchProductKnowledge", "mall_search_products", "mall_get_product_detail"),
                false,
                skill("product-compare")
        ));
        register(policies, new ShoppingTaskPolicy(
                RECOMMENDATION,
                "推荐",
                Set.of(RECOMMENDATION),
                Set.of("budget", "use_scene"),
                Set.of("searchProductKnowledge", "mall_search_products", "mall_get_product_detail"),
                false,
                skill("recommendation")
        ));
        register(policies, new ShoppingTaskPolicy(
                CART_CONFIRMATION,
                "加购确认",
                Set.of(CART_CONFIRMATION),
                Set.of("skuId", "quantity"),
                Set.of("mall_get_product_detail", "mall_add_to_cart", "mall_view_cart",
                        "mall_prepare_order", "mall_create_order"),
                true,
                skill("cart-confirmation")
        ));
        register(policies, new ShoppingTaskPolicy(
                UNKNOWN,
                "未识别",
                Set.of("UNKNOWN"),
                Set.of(),
                Set.of(),
                false,
                skill("unknown")
        ));
        this.policiesById = Collections.unmodifiableMap(policies);
    }

    public List<ShoppingTaskPolicy> resolve(ShoppingIntentRoute route) {
        if (route == null) {
            return List.of();
        }

        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();

        addDefaultPolicies(selectedIds, route);

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

    private void addDefaultPolicies(LinkedHashSet<String> selectedIds, ShoppingIntentRoute route) {
        switch (route.normalizedIntent()) {
            case PRODUCT_SELECTION -> selectedIds.add(PRODUCT_SELECTION);
            case PRODUCT_COMPARE -> {selectedIds.add(PRODUCT_COMPARE);}
            case RECOMMENDATION -> {selectedIds.add(RECOMMENDATION);}
            case CART_CONFIRMATION -> selectedIds.add(CART_CONFIRMATION);
            default -> selectedIds.add(UNKNOWN);
        }
    }

    private boolean requiresCartConfirmation(ShoppingIntentRoute route) {
        String intent = route.normalizedIntent();
        return Boolean.TRUE.equals(route.needConfirm())
                || CART_CONFIRMATION.equals(intent);
    }

    private String skill(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        return promptTemplateStore.text("skills/" + name.trim().toLowerCase(Locale.ROOT));
    }
}
