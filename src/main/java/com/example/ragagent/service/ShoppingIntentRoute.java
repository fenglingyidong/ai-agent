package com.example.ragagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ShoppingIntentRoute(
        String intent,
        @JsonProperty("task_type") String taskType,
        @JsonProperty("visual_context") Map<String, Object> visualContext,
        @JsonProperty("text_slots") Map<String, Object> textSlots,
        @JsonProperty("preference_delta") Map<String, Object> preferenceDelta,
        @JsonProperty("route_to_core") Boolean routeToCore,
        Double confidence,
        String reason,
        @JsonProperty("task_policies") List<String> taskPolicies,
        @JsonProperty("missing_slots") List<String> missingSlots,
        @JsonProperty("tool_candidates") List<String> toolCandidates,
        @JsonProperty("need_confirm") Boolean needConfirm,
        @JsonProperty("risk_level") String riskLevel
) {

    public ShoppingIntentRoute {
        intent = StringUtils.hasText(intent) ? intent.trim() : "UNKNOWN";
        taskType = normalizeTaskType(taskType, intent);
        visualContext = immutableNonNullMap(visualContext);
        textSlots = immutableNonNullMap(textSlots);
        preferenceDelta = immutableNonNullMap(preferenceDelta);
        routeToCore = routeToCore == null ? defaultRouteToCore(taskType) : routeToCore;
        confidence = confidence == null ? 0.0 : Math.max(0.0, Math.min(1.0, confidence));
        reason = StringUtils.hasText(reason) ? reason.trim() : "";
        taskPolicies = immutableStringList(taskPolicies);
        missingSlots = immutableStringList(missingSlots);
        toolCandidates = immutableStringList(toolCandidates);
        needConfirm = needConfirm == null ? Boolean.FALSE : needConfirm;
        riskLevel = normalizeRiskLevel(riskLevel);
    }

    public ShoppingIntentRoute(String intent,
                               String taskType,
                               Map<String, Object> visualContext,
                               Map<String, Object> textSlots,
                               Boolean routeToCore,
                               Double confidence,
                               String reason) {
        this(intent, taskType, visualContext, textSlots, Map.of(), routeToCore, confidence, reason,
                List.of(), List.of(), List.of(), false, "LOW");
    }

    public ShoppingIntentRoute(String intent,
                               String taskType,
                               Map<String, Object> visualContext,
                               Map<String, Object> textSlots,
                               Boolean routeToCore,
                               Double confidence,
                               String reason,
                               List<String> taskPolicies,
                               List<String> missingSlots,
                               List<String> toolCandidates,
                               Boolean needConfirm,
                               String riskLevel) {
        this(intent, taskType, visualContext, textSlots, Map.of(), routeToCore, confidence, reason,
                taskPolicies, missingSlots, toolCandidates, needConfirm, riskLevel);
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
        return new ShoppingIntentRoute("UNKNOWN", "COMPLEX_REACT", Map.of(), Map.of(), Map.of(), true, 0.0, reason,
                List.of(), List.of(), List.of(), false, "LOW");
    }

    public String normalizedIntent() {
        return StringUtils.hasText(intent) ? intent.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    public String normalizedTaskType() {
        return StringUtils.hasText(taskType) ? taskType.trim().toUpperCase(Locale.ROOT) : "COMPLEX_REACT";
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

    public boolean hasMissingSlots() {
        return !missingSlots.isEmpty();
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

    private static List<String> immutableStringList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> target = new ArrayList<>();
        for (String value : source) {
            if (StringUtils.hasText(value)) {
                target.add(value.trim());
            }
        }
        return target.isEmpty() ? List.of() : List.copyOf(target);
    }

    private static String normalizeRiskLevel(String riskLevel) {
        if (!StringUtils.hasText(riskLevel)) {
            return "LOW";
        }
        String normalized = riskLevel.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH" -> normalized;
            default -> "LOW";
        };
    }

    private static String normalizeTaskType(String taskType, String intent) {
        if (StringUtils.hasText(taskType)) {
            String normalized = taskType.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "FAQ_SIMPLE_QUERY", "A_FAQ_SIMPLE_QUERY" -> "FAQ_SIMPLE_QUERY";
                case "SIMPLE_SHOPPING_TOOL", "B_SIMPLE_SHOPPING_TOOL" -> "SIMPLE_SHOPPING_TOOL";
                case "COMPLEX_REACT", "C_COMPLEX_REACT" -> "COMPLEX_REACT";
                default -> deriveTaskType(intent);
            };
        }
        return deriveTaskType(intent);
    }

    private static String deriveTaskType(String intent) {
        String normalizedIntent = StringUtils.hasText(intent) ? intent.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
        return switch (normalizedIntent) {
            case "FAQ_SIMPLE_QUERY", "PRODUCT_KNOWLEDGE_QUERY" -> "FAQ_SIMPLE_QUERY";
            case "QUERY_ATTRIBUTE", "PRICE_STOCK_QUERY", "VIEW_CART", "ADD_TO_CART", "PREPARE_ORDER" -> "SIMPLE_SHOPPING_TOOL";
            default -> "COMPLEX_REACT";
        };
    }

    private static boolean defaultRouteToCore(String taskType) {
        return "COMPLEX_REACT".equals(taskType);
    }
}
