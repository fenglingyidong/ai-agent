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

    private static final Pattern BUDGET_RANGE = Pattern.compile("(\\d+)\\s*(?:-|到|至|~|－|—)\\s*(\\d+)");
    private static final Pattern BUDGET_MAX = Pattern.compile("预算\\s*(\\d+)");
    private static final String[] CATEGORIES = {
            "降噪耳机", "儿童积木", "运动鞋", "跑鞋", "积木", "耳机",
            "书包", "手机", "电脑", "外套", "裙子", "玩具"
    };

    public ShoppingStateService.ShoppingPreferencePatch extract(String userMessage,
                                                                ShoppingIntentRoute route,
                                                                Long turnNo) {
        String text = StringUtils.hasText(userMessage) ? userMessage.trim() : "";
        Integer budgetMin = null;
        Integer budgetMax = null;

        Matcher rangeMatcher = BUDGET_RANGE.matcher(text);
        if (rangeMatcher.find()) {
            budgetMin = parsePositiveInt(rangeMatcher.group(1));
            budgetMax = parsePositiveInt(rangeMatcher.group(2));
        }
        else {
            Matcher maxMatcher = BUDGET_MAX.matcher(text);
            if (maxMatcher.find()) {
                budgetMax = parsePositiveInt(maxMatcher.group(1));
            }
        }

        String category = extractCategory(text);
        String brand = null;
        String size = null;
        String color = null;
        String style = null;
        String usageScenario = null;
        boolean hasRouteSlot = false;

        SlotValues textSlots = readSlots(route == null ? null : route.textSlots());
        if (textSlots.hasAny()) {
            category = defaultIfBlank(textSlots.category(), category);
            brand = defaultIfBlank(textSlots.brand(), brand);
            size = defaultIfBlank(textSlots.size(), size);
            color = defaultIfBlank(textSlots.color(), color);
            style = defaultIfBlank(textSlots.style(), style);
            usageScenario = defaultIfBlank(textSlots.usageScenario(), usageScenario);
            hasRouteSlot = true;
        }

        SlotValues visualSlots = readSlots(route == null ? null : route.visualContext());
        if (visualSlots.hasAny()) {
            if (!StringUtils.hasText(textSlots.category())) {
                category = defaultIfBlank(visualSlots.category(), category);
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
            hasRouteSlot = true;
        }

        Set<String> clearFields = extractClearFields(text);
        boolean hasAnyField = StringUtils.hasText(category)
                || budgetMin != null
                || budgetMax != null
                || StringUtils.hasText(brand)
                || StringUtils.hasText(size)
                || StringUtils.hasText(color)
                || StringUtils.hasText(style)
                || StringUtils.hasText(usageScenario)
                || !clearFields.isEmpty();

        return new ShoppingStateService.ShoppingPreferencePatch(
                category,
                budgetMin,
                budgetMax,
                brand,
                size,
                color,
                style,
                usageScenario,
                clearFields,
                hasRouteSlot ? ShoppingPreferenceSource.ROUTER_SLOT.name() : ShoppingPreferenceSource.USER_EXPLICIT.name(),
                hasAnyField ? 1.0 : 0.0,
                turnNo
        );
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

    private SlotValues readSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return SlotValues.empty();
        }
        return new SlotValues(
                readSlot(slots, "category"),
                readSlot(slots, "brand"),
                readSlot(slots, "size"),
                readSlot(slots, "color"),
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

    private record SlotValues(String category,
                              String brand,
                              String size,
                              String color,
                              String style,
                              String usageScenario) {

        static SlotValues empty() {
            return new SlotValues(null, null, null, null, null, null);
        }

        boolean hasAny() {
            return StringUtils.hasText(category)
                    || StringUtils.hasText(brand)
                    || StringUtils.hasText(size)
                    || StringUtils.hasText(color)
                    || StringUtils.hasText(style)
                    || StringUtils.hasText(usageScenario);
        }
    }
}
