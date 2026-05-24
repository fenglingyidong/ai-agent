package com.example.ragagent.commerce;

import com.example.ragagent.service.ShoppingIntentRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingPreferenceExtractorTest {

    private final ShoppingPreferenceExtractor extractor = new ShoppingPreferenceExtractor();

    @Test
    void extractShouldReadBudgetAndCategoryFromUserText() {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "预算500以内，帮我推荐通勤跑鞋",
                null,
                1L
        );

        assertEquals("跑鞋", patch.category());
        assertEquals(500, patch.budgetMax());
        assertEquals(ShoppingPreferenceSource.USER_EXPLICIT.name(), patch.source());
        assertEquals(1L, patch.turnNo());
    }

    @Test
    void extractShouldReadRouteSlotsWithHigherStructure() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "C_COMPLEX_REACT",
                "COMPLEX_RECOMMENDATION",
                Map.of(),
                Map.of("category", "儿童积木", "brand", "乐高", "use_scene", "生日礼物"),
                true,
                0.93,
                "复杂推荐",
                java.util.List.of("RECOMMENDATION"),
                java.util.List.of(),
                java.util.List.of(),
                false,
                "LOW"
        );

        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "预算300给5岁孩子买生日礼物",
                route,
                2L
        );

        assertEquals("儿童积木", patch.category());
        assertEquals("乐高", patch.brand());
        assertEquals("生日礼物", patch.usageScenario());
        assertEquals(300, patch.budgetMax());
        assertEquals(ShoppingPreferenceSource.ROUTER_SLOT.name(), patch.source());
    }

    @Test
    void extractShouldClearFieldsWhenUserSaysUnlimited() {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "品牌不限，预算也不用卡太死",
                null,
                3L
        );

        assertTrue(patch.clearFields().contains("brand"));
        assertTrue(patch.clearFields().contains("budget"));
    }

    @Test
    void extractShouldNotTreatAgeRangeAsBudgetRange() {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "5-7岁孩子，预算300",
                null,
                4L
        );

        assertEquals(null, patch.budgetMin());
        assertEquals(300, patch.budgetMax());
    }

    @ParameterizedTest
    @ValueSource(strings = {"300-500", "300到500"})
    void extractShouldReadPlainBudgetRangeFromUserText(String userMessage) {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                userMessage,
                null,
                1L
        );

        assertEquals(300, patch.budgetMin());
        assertEquals(500, patch.budgetMax());
    }

    @Test
    void extractShouldReadBudgetFromRouteSlots() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "C_COMPLEX_REACT",
                "COMPLEX_RECOMMENDATION",
                Map.of(),
                Map.of("budget", "300-500"),
                true,
                0.87,
                "复杂推荐",
                java.util.List.of("RECOMMENDATION"),
                java.util.List.of(),
                java.util.List.of(),
                false,
                "LOW"
        );

        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "帮我推荐生日礼物",
                route,
                5L
        );

        assertEquals(300, patch.budgetMin());
        assertEquals(500, patch.budgetMax());
        assertEquals(ShoppingPreferenceSource.ROUTER_SLOT.name(), patch.source());
        assertEquals(0.87, patch.confidence());
    }

    @Test
    void extractShouldIgnoreReversedBudgetRangeFromUserText() {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "预算500-300，帮我推荐跑鞋",
                null,
                7L
        );

        assertEquals(null, patch.budgetMin());
        assertEquals(null, patch.budgetMax());
        assertEquals("跑鞋", patch.category());
    }

    @Test
    void extractShouldIgnorePlainProductPriceAsBudget() {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "这款199元的耳机怎么样",
                null,
                8L
        );

        assertEquals(null, patch.budgetMax());
        assertEquals("耳机", patch.category());
    }

    @ParameterizedTest
    @ValueSource(strings = {"不超过500元", "最多500元", "500元以内的跑鞋"})
    void extractShouldReadExplicitBudgetUpperBound(String userMessage) {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                userMessage,
                null,
                9L
        );

        assertEquals(500, patch.budgetMax());
    }

    @ParameterizedTest
    @ValueSource(strings = {"最多推荐3款跑鞋", "不超过3双", "推荐3款以内"})
    void extractShouldIgnoreQuantityLimitAsBudget(String userMessage) {
        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                userMessage,
                null,
                11L
        );

        assertEquals(null, patch.budgetMin());
        assertEquals(null, patch.budgetMax());
    }

    @Test
    void extractShouldIgnoreReversedBudgetRangeFromRouteSlot() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "C_COMPLEX_REACT",
                "COMPLEX_RECOMMENDATION",
                Map.of(),
                Map.of("budget", "500-300", "category", "跑鞋"),
                true,
                0.87,
                "复杂推荐",
                java.util.List.of("RECOMMENDATION"),
                java.util.List.of(),
                java.util.List.of(),
                false,
                "LOW"
        );

        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "帮我推荐",
                route,
                10L
        );

        assertEquals(null, patch.budgetMin());
        assertEquals(null, patch.budgetMax());
        assertEquals("跑鞋", patch.category());
    }

    @Test
    void extractShouldReadVisualAliases() {
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "C_COMPLEX_REACT",
                "COMPLEX_RECOMMENDATION",
                Map.of("brand_logo", "Nike", "main_color", "黑色"),
                Map.of(),
                true,
                0.76,
                "复杂推荐",
                java.util.List.of("RECOMMENDATION"),
                java.util.List.of(),
                java.util.List.of(),
                false,
                "LOW"
        );

        ShoppingStateService.ShoppingPreferencePatch patch = extractor.extract(
                "这张图里的同款",
                route,
                6L
        );

        assertEquals("Nike", patch.brand());
        assertEquals("黑色", patch.color());
        assertEquals(ShoppingPreferenceSource.VISUAL_CONTEXT.name(), patch.source());
        assertEquals(0.76, patch.confidence());
    }
}
