package com.example.ragagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ShoppingIntentRoute(
        String intent,
        @JsonProperty("task_type") String taskType,
        @JsonProperty("visual_context") Map<String, Object> visualContext,
        @JsonProperty("text_slots") Map<String, Object> textSlots,
        @JsonProperty("route_to_core") Boolean routeToCore,
        Double confidence,
        String reason
) {

    public ShoppingIntentRoute {
        intent = StringUtils.hasText(intent) ? intent.trim() : "UNKNOWN";
        taskType = normalizeTaskType(taskType, intent);
        visualContext = immutableNonNullMap(visualContext);
        textSlots = immutableNonNullMap(textSlots);
        routeToCore = routeToCore == null ? Boolean.TRUE : routeToCore;
        confidence = confidence == null ? 0.0 : Math.max(0.0, Math.min(1.0, confidence));
        reason = StringUtils.hasText(reason) ? reason.trim() : "";
    }

    public ShoppingIntentRoute(String intent,
                               Map<String, Object> visualContext,
                               Map<String, Object> textSlots,
                               Boolean routeToCore,
                               Double confidence,
                               String reason) {
        this(intent, null, visualContext, textSlots, routeToCore, confidence, reason);
    }

    public static ShoppingIntentRoute fallback(String reason) {
        return new ShoppingIntentRoute("UNKNOWN", "C_COMPLEX_REACT", Map.of(), Map.of(), true, 0.0, reason);
    }

    public String normalizedIntent() {
        return StringUtils.hasText(intent) ? intent.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    public String normalizedTaskType() {
        return StringUtils.hasText(taskType) ? taskType.trim().toUpperCase(Locale.ROOT) : "C_COMPLEX_REACT";
    }

    public boolean isHighConfidence(double threshold) {
        return confidence != null && confidence >= threshold;
    }

    public boolean hasVisualContext() {
        return !visualContext.isEmpty();
    }

    public boolean hasTextSlots() {
        return !textSlots.isEmpty();
    }

    private static Map<String, Object> immutableNonNullMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> target = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                target.put(key.trim(), value);
            }
        });
        return target.isEmpty() ? Map.of() : Collections.unmodifiableMap(target);
    }

    private static String normalizeTaskType(String taskType, String intent) {
        if (StringUtils.hasText(taskType)) {
            String normalized = taskType.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "A_FAQ_SIMPLE_QUERY", "B_SIMPLE_SHOPPING_TOOL", "C_COMPLEX_REACT" -> normalized;
                default -> deriveTaskType(intent);
            };
        }
        return deriveTaskType(intent);
    }

    private static String deriveTaskType(String intent) {
        String normalizedIntent = StringUtils.hasText(intent) ? intent.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
        return switch (normalizedIntent) {
            case "FAQ_SIMPLE_QUERY", "PRODUCT_KNOWLEDGE_QUERY" -> "A_FAQ_SIMPLE_QUERY";
            case "QUERY_ATTRIBUTE", "PRICE_STOCK_QUERY", "VIEW_CART", "ADD_TO_CART", "PREPARE_ORDER" -> "B_SIMPLE_SHOPPING_TOOL";
            default -> "C_COMPLEX_REACT";
        };
    }
}
