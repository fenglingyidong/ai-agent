package com.example.ragagent.commerce;

import com.example.ragagent.service.ShoppingIntentRoute;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户文本、路由槽位和视觉上下文中提取短期导购偏好补丁。
 */
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

    /**
     * 合并显式文本、路由槽位和视觉槽位，生成可写入会话偏好的增量补丁。
     */
    public ShoppingStateService.ShoppingPreferencePatch extract(String userMessage,
                                                                ShoppingIntentRoute route,
                                                                Long turnNo) {
        String text = StringUtils.hasText(userMessage) ? userMessage.trim() : "";
        Integer budget = extractBudgetFromText(text);

        String category = extractCategory(text);
        String brand = null;
        String size = null;
        String color = null;
        String style = null;
        String usageScenario = null;
        boolean hasTextSlot = false;
        boolean hasVisualSlot = false;
        boolean hasPreferenceDelta = false;

        SlotValues preferenceDelta = readSlots(route == null ? null : route.preferenceDelta(), false);
        if (preferenceDelta.hasAny()) {
            category = defaultIfBlank(preferenceDelta.category(), category);
            budget = defaultIfNull(preferenceDelta.budget(), budget);
            brand = defaultIfBlank(preferenceDelta.brand(), brand);
            size = defaultIfBlank(preferenceDelta.size(), size);
            color = defaultIfBlank(preferenceDelta.color(), color);
            style = defaultIfBlank(preferenceDelta.style(), style);
            usageScenario = defaultIfBlank(preferenceDelta.usageScenario(), usageScenario);
            hasPreferenceDelta = true;
        }

        SlotValues textSlots = readSlots(route == null ? null : route.textSlots(), false);
        if (textSlots.hasAny()) {
            category = defaultIfBlank(textSlots.category(), category);
            budget = defaultIfNull(textSlots.budget(), budget);
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
            if (textSlots.budget() == null) {
                budget = defaultIfNull(visualSlots.budget(), budget);
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
        clearFields.addAll(extractClearFieldsFromRoute(route == null ? null : route.preferenceDelta()));
        boolean hasAnyField = StringUtils.hasText(category)
                || budget != null
                || StringUtils.hasText(brand)
                || StringUtils.hasText(size)
                || StringUtils.hasText(color)
                || StringUtils.hasText(style)
                || StringUtils.hasText(usageScenario)
                || !clearFields.isEmpty();
        String source = resolveSource(hasPreferenceDelta || hasTextSlot, hasVisualSlot);
        double confidence = resolveConfidence(route, hasPreferenceDelta || hasTextSlot || hasVisualSlot, hasAnyField);

        return new ShoppingStateService.ShoppingPreferencePatch(
                category,
                budget,
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

    private Integer extractBudgetFromText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
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
            return max;
        }
        max = matchBudgetMax(BUDGET_MAX_WITH_LIMIT_PREFIX, text);
        if (max != null) {
            return max;
        }
        max = matchBudgetMax(BUDGET_MAX_WITH_UNIT_SUFFIX, text);
        return max;
    }

    private Integer extractBudgetFromSlot(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = raw.toString().trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        BudgetRangeMatch range = matchBudgetRange(BUDGET_SLOT_RANGE, text);
        if (range.matched()) {
            return range.budget();
        }
        Integer max = matchBudgetMax(Pattern.compile("(\\d+)"), text);
        return max;
    }

    private Integer extractBudgetFromSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        Integer budget = extractBudgetFromSlot(slots.get("budget"));
        Integer min = parsePositiveNumber(slots.get("budget_min"));
        Integer max = parsePositiveNumber(slots.get("budget_max"));
        if (min != null || max != null) {
            return max != null ? max : min;
        }
        return budget;
    }

    private BudgetRangeMatch matchBudgetRange(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return BudgetRangeMatch.notMatched();
        }
        Integer min = parsePositiveInt(matcher.group(1));
        Integer max = parsePositiveInt(matcher.group(2));
        if (min == null || max == null || min > max) {
            return BudgetRangeMatch.matched(null);
        }
        return BudgetRangeMatch.matched(max);
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

    private Set<String> extractClearFieldsFromRoute(Map<String, Object> slots) {
        Set<String> clearFields = new LinkedHashSet<>();
        if (slots == null || slots.isEmpty()) {
            return clearFields;
        }
        Object raw = slots.get("clear_fields");
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                addClearField(clearFields, value);
            }
            return clearFields;
        }
        addClearField(clearFields, raw);
        return clearFields;
    }

    private void addClearField(Set<String> clearFields, Object raw) {
        if (raw == null) {
            return;
        }
        String value = raw.toString().trim();
        if (!StringUtils.hasText(value)) {
            return;
        }
        switch (value) {
            case "brand", "budget", "category", "size", "color", "style", "usage_scenario" -> clearFields.add(value);
            default -> {
            }
        }
    }

    private SlotValues readSlots(Map<String, Object> slots, boolean visualContext) {
        if (slots == null || slots.isEmpty()) {
            return SlotValues.empty();
        }
        return new SlotValues(
                readSlot(slots, "category"),
                visualContext ? readSlot(slots, "brand", "brand_logo") : readSlot(slots, "brand"),
                extractBudgetFromSlots(slots),
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

    private Integer parsePositiveNumber(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            int value = number.intValue();
            return value > 0 ? value : null;
        }
        String text = raw.toString().trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d+)").matcher(text);
        return matcher.find() ? parsePositiveInt(matcher.group(1)) : null;
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

    /**
     * 区分“没有命中预算表达式”和“命中但值无效”的解析结果。
     */
    private record BudgetRangeMatch(boolean matched, Integer budget) {

        static BudgetRangeMatch notMatched() {
            return new BudgetRangeMatch(false, null);
        }

        static BudgetRangeMatch matched(Integer budget) {
            return new BudgetRangeMatch(true, budget);
        }
    }

    /**
     * 统一承载路由文本槽位、视觉槽位和偏好增量中的导购字段。
     */
    private record SlotValues(String category,
                              String brand,
                              Integer budget,
                              String size,
                              String color,
                              String style,
                              String usageScenario) {

        static SlotValues empty() {
            return new SlotValues(null, null, null, null, null, null, null);
        }

        boolean hasAny() {
            return StringUtils.hasText(category)
                    || StringUtils.hasText(brand)
                    || budget != null
                    || StringUtils.hasText(size)
                    || StringUtils.hasText(color)
                    || StringUtils.hasText(style)
                    || StringUtils.hasText(usageScenario);
        }
    }

    private Integer defaultIfNull(Integer value, Integer fallback) {
        return value != null ? value : fallback;
    }
}
