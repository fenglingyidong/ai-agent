package com.example.ragagent.commerce;

import com.example.ragagent.service.ShoppingIntentRoute;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ShoppingPreferenceExtractor {

    private static final Pattern BUDGET_RANGE_WITH_PREFIX = Pattern.compile("(?:预算|价格|价位)\\s*(\\d+)\\s*(?:-|到|至|~|－|—)\\s*(\\d+)");
    private static final Pattern BUDGET_RANGE_WITH_SUFFIX = Pattern.compile("(\\d+)\\s*(?:-|到|至|~|－|—)\\s*(\\d+)\\s*(?:元|块)");
    private static final Pattern BUDGET_RANGE_STANDALONE = Pattern.compile("^\\s*(\\d+)\\s*(?:-|到|至|~|－|—)\\s*(\\d+)\\s*$");
    private static final Pattern BUDGET_SLOT_RANGE = Pattern.compile("(\\d+)\\s*(?:-|到|至|~|－|—)\\s*(\\d+)");
    private static final Pattern BUDGET_MAX_WITH_BUDGET_PREFIX = Pattern.compile("(?:预算|价位)\\D{0,6}?(\\d+)");
    private static final Pattern BUDGET_MAX_WITH_LIMIT_PREFIX = Pattern.compile("(?:不超过|最多)\\D{0,6}?(\\d+)\\s*(?:元|块)");
    private static final Pattern BUDGET_MAX_WITH_UNIT_SUFFIX = Pattern.compile("(\\d+)\\s*(?:元|块)\\s*(?:以内|以下|内)");
    private static final String[] CATEGORIES = {
            "降噪耳机", "儿童积木", "运动鞋", "跑鞋", "积木", "耳机",
            "书包", "手机", "电脑", "外套", "裙子", "玩具"
    };

    public ShoppingStateService.ShoppingPreferencePatch extract(String userMessage,
                                                                ShoppingIntentRoute route,
                                                                Long turnNo) {
        String text = StringUtils.hasText(userMessage) ? userMessage.trim() : "";
        BudgetValues budget = extractBudgetFromText(text);

        String category = extractCategory(text);
        String brand = null;
        String size = null;
        String color = null;
        String style = null;
        String usageScenario = null;
        boolean hasTextSlot = false;
        boolean hasVisualSlot = false;

        SlotValues textSlots = readSlots(route == null ? null : route.textSlots(), false);
        if (textSlots.hasAny()) {
            category = defaultIfBlank(textSlots.category(), category);
            budget = budget.overrideWith(textSlots.budget());
            brand = defaultIfBlank(textSlots.brand(), brand);
            size = defaultIfBlank(textSlots.size(), size);
            color = defaultIfBlank(textSlots.color(), color);
            style = defaultIfBlank(textSlots.style(), style);
            usageScenario = defaultIfBlank(textSlots.usageScenario(), usageScenario);
            hasTextSlot = true;
        }

        SlotValues visualSlots = readSlots(route == null ? null : route.visualContext(), true);
        if (visualSlots.hasAny()) {
            if (!StringUtils.hasText(textSlots.category())) {
                category = defaultIfBlank(visualSlots.category(), category);
            }
            if (!textSlots.budget().hasAny()) {
                budget = budget.overrideWith(visualSlots.budget());
            }
            if (!StringUtils.hasText(textSlots.brand())) {
                brand = defaultIfBlank(visualSlots.brand(), brand);
            }
            if (!StringUtils.hasText(textSlots.size())) {
                size = defaultIfBlank(visualSlots.size(), size);
            }
            if (!StringUtils.hasText(textSlots.color())) {
                color = defaultIfBlank(visualSlots.color(), color);
            }
            if (!StringUtils.hasText(textSlots.style())) {
                style = defaultIfBlank(visualSlots.style(), style);
            }
            if (!StringUtils.hasText(textSlots.usageScenario())) {
                usageScenario = defaultIfBlank(visualSlots.usageScenario(), usageScenario);
            }
            hasVisualSlot = true;
        }

        Set<String> clearFields = extractClearFields(text);
        boolean hasAnyField = StringUtils.hasText(category)
                || budget.hasAny()
                || StringUtils.hasText(brand)
                || StringUtils.hasText(size)
                || StringUtils.hasText(color)
                || StringUtils.hasText(style)
                || StringUtils.hasText(usageScenario)
                || !clearFields.isEmpty();
        String source = resolveSource(hasTextSlot, hasVisualSlot);
        double confidence = resolveConfidence(route, hasTextSlot || hasVisualSlot, hasAnyField);

        return new ShoppingStateService.ShoppingPreferencePatch(
                category,
                budget.min(),
                budget.max(),
                brand,
                size,
                color,
                style,
                usageScenario,
                clearFields,
                source,
                confidence,
                turnNo
        );
    }

    private BudgetValues extractBudgetFromText(String text) {
        if (!StringUtils.hasText(text)) {
            return BudgetValues.empty();
        }
        BudgetRangeMatch range = matchBudgetRange(BUDGET_RANGE_WITH_PREFIX, text);
        if (range.matched()) {
            return range.budget();
        }
        range = matchBudgetRange(BUDGET_RANGE_WITH_SUFFIX, text);
        if (range.matched()) {
            return range.budget();
        }
        range = matchBudgetRange(BUDGET_RANGE_STANDALONE, text);
        if (range.matched()) {
            return range.budget();
        }
        Integer max = matchBudgetMax(BUDGET_MAX_WITH_BUDGET_PREFIX, text);
        if (max != null) {
            return new BudgetValues(null, max);
        }
        max = matchBudgetMax(BUDGET_MAX_WITH_LIMIT_PREFIX, text);
        if (max != null) {
            return new BudgetValues(null, max);
        }
        max = matchBudgetMax(BUDGET_MAX_WITH_UNIT_SUFFIX, text);
        return max == null ? BudgetValues.empty() : new BudgetValues(null, max);
    }

    private BudgetValues extractBudgetFromSlot(Object raw) {
        if (raw == null) {
            return BudgetValues.empty();
        }
        if (raw instanceof Number number) {
            return new BudgetValues(null, number.intValue());
        }
        String text = raw.toString().trim();
        if (!StringUtils.hasText(text)) {
            return BudgetValues.empty();
        }
        BudgetRangeMatch range = matchBudgetRange(BUDGET_SLOT_RANGE, text);
        if (range.matched()) {
            return range.budget();
        }
        Integer max = matchBudgetMax(Pattern.compile("(\\d+)"), text);
        return max == null ? BudgetValues.empty() : new BudgetValues(null, max);
    }

    private BudgetRangeMatch matchBudgetRange(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return BudgetRangeMatch.notMatched();
        }
        Integer min = parsePositiveInt(matcher.group(1));
        Integer max = parsePositiveInt(matcher.group(2));
        if (min == null || max == null || min > max) {
            return BudgetRangeMatch.matched(BudgetValues.empty());
        }
        return BudgetRangeMatch.matched(new BudgetValues(min, max));
    }

    private Integer matchBudgetMax(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? parsePositiveInt(matcher.group(1)) : null;
    }

    private String extractCategory(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        for (String category : CATEGORIES) {
            if (text.contains(category)) {
                return category;
            }
        }
        return null;
    }

    private Set<String> extractClearFields(String text) {
        Set<String> clearFields = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return clearFields;
        }
        if (text.contains("不限品牌")
                || text.contains("品牌不限")
                || text.contains("不要限定品牌")
                || text.contains("无品牌要求")) {
            clearFields.add("brand");
        }
        if (text.matches(".*预算.*不用卡太死.*")
                || text.contains("预算不限")
                || text.contains("不限制预算")
                || text.contains("价格不限")) {
            clearFields.add("budget");
        }
        return clearFields;
    }

    private SlotValues readSlots(Map<String, Object> slots, boolean visualContext) {
        if (slots == null || slots.isEmpty()) {
            return SlotValues.empty();
        }
        return new SlotValues(
                readSlot(slots, "category"),
                visualContext ? readSlot(slots, "brand", "brand_logo") : readSlot(slots, "brand"),
                extractBudgetFromSlot(slots.get("budget")),
                readSlot(slots, "size"),
                visualContext ? readSlot(slots, "color", "main_color") : readSlot(slots, "color"),
                readSlot(slots, "style"),
                readSlot(slots, "usageScenario", "usage_scenario", "use_scene", "scene", "使用场景")
        );
    }

    private String readSlot(Map<String, Object> slots, String... keys) {
        for (String key : keys) {
            Object value = slots.get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private Integer parsePositiveInt(String raw) {
        try {
            return Integer.valueOf(raw);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private String resolveSource(boolean hasTextSlot, boolean hasVisualSlot) {
        if (hasTextSlot) {
            return ShoppingPreferenceSource.ROUTER_SLOT.name();
        }
        if (hasVisualSlot) {
            return ShoppingPreferenceSource.VISUAL_CONTEXT.name();
        }
        return ShoppingPreferenceSource.USER_EXPLICIT.name();
    }

    private double resolveConfidence(ShoppingIntentRoute route, boolean hasRouteSignal, boolean hasAnyField) {
        if (!hasAnyField) {
            return 0.0;
        }
        if (hasRouteSignal && route != null) {
            return route.confidence();
        }
        return 1.0;
    }

    private record BudgetValues(Integer min, Integer max) {

        static BudgetValues empty() {
            return new BudgetValues(null, null);
        }

        boolean hasAny() {
            return min != null || max != null;
        }

        BudgetValues overrideWith(BudgetValues other) {
            return other != null && other.hasAny() ? other : this;
        }
    }

    private record BudgetRangeMatch(boolean matched, BudgetValues budget) {

        static BudgetRangeMatch notMatched() {
            return new BudgetRangeMatch(false, BudgetValues.empty());
        }

        static BudgetRangeMatch matched(BudgetValues budget) {
            return new BudgetRangeMatch(true, budget);
        }
    }

    private record SlotValues(String category,
                              String brand,
                              BudgetValues budget,
                              String size,
                              String color,
                              String style,
                              String usageScenario) {

        static SlotValues empty() {
            return new SlotValues(null, null, BudgetValues.empty(), null, null, null, null);
        }

        boolean hasAny() {
            return StringUtils.hasText(category)
                    || StringUtils.hasText(brand)
                    || budget.hasAny()
                    || StringUtils.hasText(size)
                    || StringUtils.hasText(color)
                    || StringUtils.hasText(style)
                    || StringUtils.hasText(usageScenario);
        }
    }
}
