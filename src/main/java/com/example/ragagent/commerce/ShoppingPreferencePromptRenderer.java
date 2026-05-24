package com.example.ragagent.commerce;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ShoppingPreferencePromptRenderer {

    public String render(ShoppingPreferenceState state) {
        if (isEmpty(state)) {
            return "";
        }

        StringBuilder builder = new StringBuilder("当前会话短期导购偏好：");
        appendLine(builder, "品类", state.getCategory());
        appendLine(builder, "预算", renderBudget(state));
        appendLine(builder, "品牌", state.getBrand());
        appendLine(builder, "尺码", state.getSize());
        appendLine(builder, "颜色", state.getColor());
        appendLine(builder, "风格", state.getStyle());
        appendLine(builder, "使用场景", state.getUsageScenario());
        builder.append(System.lineSeparator())
                .append("如果用户本轮明确修改偏好，以本轮为准；不要把未确认的旧偏好当作硬约束。");
        return builder.toString();
    }

    private boolean isEmpty(ShoppingPreferenceState state) {
        return state == null
                || (!StringUtils.hasText(state.getCategory())
                && state.getBudgetMin() == null
                && state.getBudgetMax() == null
                && !StringUtils.hasText(state.getBrand())
                && !StringUtils.hasText(state.getSize())
                && !StringUtils.hasText(state.getColor())
                && !StringUtils.hasText(state.getStyle())
                && !StringUtils.hasText(state.getUsageScenario()));
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(label)
                    .append("：")
                    .append(value.trim());
        }
    }

    private String renderBudget(ShoppingPreferenceState state) {
        Integer min = state.getBudgetMin();
        Integer max = state.getBudgetMax();
        if (min != null && max != null) {
            return min + "-" + max;
        }
        if (max != null) {
            return max + "以内";
        }
        if (min != null) {
            return min + "以上";
        }
        return "";
    }
}
