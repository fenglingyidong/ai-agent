package com.example.ragagent.commerce;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将导购偏好增量安全合并到当前会话偏好状态。
 */
@Component
public class ShoppingPreferenceMergePolicy {

    /**
     * 应用清空字段、类别切换和新偏好字段，返回更新后的状态对象。
     */
    public ShoppingPreferenceState merge(ShoppingPreferenceState current,
                                         ShoppingStateService.ShoppingPreferencePatch patch) {
        ShoppingPreferenceState state = current == null ? new ShoppingPreferenceState() : current;

        clearFields(state, patch);
        if (StringUtils.hasText(patch.category())) {
            String category = patch.category().trim();
            if (!category.equals(state.getCategory())) {
                clearCategorySpecificFields(state);
            }
            state.setCategory(category);
        }
        if (patch.budget() != null) {
            state.setBudget(patch.budget());
        }
        if (StringUtils.hasText(patch.brand())) {
            state.setBrand(patch.brand().trim());
        }
        if (StringUtils.hasText(patch.size())) {
            state.setSize(patch.size().trim());
        }
        if (StringUtils.hasText(patch.color())) {
            state.setColor(patch.color().trim());
        }
        if (StringUtils.hasText(patch.style())) {
            state.setStyle(patch.style().trim());
        }
        if (StringUtils.hasText(patch.usageScenario())) {
            state.setUsageScenario(patch.usageScenario().trim());
        }

        state.setSource(normalizeSource(patch.source()).name());
        state.setConfidence(patch.confidence());
        state.setUpdatedTurnNo(patch.turnNo());
        state.setUpdatedAtEpochMillis(System.currentTimeMillis());
        return state;
    }

    private void clearFields(ShoppingPreferenceState state, ShoppingStateService.ShoppingPreferencePatch patch) {
        for (String field : patch.clearFields()) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            switch (field.trim()) {
                case "category" -> state.setCategory(null);
                case "budget", "budgetMin", "budgetMax" -> state.setBudget(null);
                case "brand" -> state.setBrand(null);
                case "size" -> state.setSize(null);
                case "color" -> state.setColor(null);
                case "style" -> state.setStyle(null);
                case "usageScenario", "use_scene", "scene" -> state.setUsageScenario(null);
                default -> {
                    // 忽略未知清空字段，避免上游槽位扩展影响现有状态。
                }
            }
        }
    }

    private void clearCategorySpecificFields(ShoppingPreferenceState state) {
        state.setBrand(null);
        state.setSize(null);
        state.setColor(null);
        state.setStyle(null);
        state.setUsageScenario(null);
    }

    private ShoppingPreferenceSource normalizeSource(String source) {
        if (!StringUtils.hasText(source)) {
            return ShoppingPreferenceSource.USER_EXPLICIT;
        }
        try {
            return ShoppingPreferenceSource.valueOf(source.trim());
        }
        catch (IllegalArgumentException ex) {
            return ShoppingPreferenceSource.USER_EXPLICIT;
        }
    }
}
