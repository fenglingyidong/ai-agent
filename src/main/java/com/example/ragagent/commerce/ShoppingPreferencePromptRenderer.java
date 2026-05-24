package com.example.ragagent.commerce;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ShoppingPreferencePromptRenderer {

    private static final int MAX_FIELD_LENGTH = 80;

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
                || (!StringUtils.hasText(sanitize(state.getCategory()))
                && !StringUtils.hasText(renderBudget(state))
                && !StringUtils.hasText(sanitize(state.getBrand()))
                && !StringUtils.hasText(sanitize(state.getSize()))
                && !StringUtils.hasText(sanitize(state.getColor()))
                && !StringUtils.hasText(sanitize(state.getStyle()))
                && !StringUtils.hasText(sanitize(state.getUsageScenario())));
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        String safeValue = sanitize(value);
        if (StringUtils.hasText(safeValue)) {
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(label)
                    .append("：")
                    .append(safeValue);
        }
    }

    private String renderBudget(ShoppingPreferenceState state) {
        Integer min = state.getBudgetMin();
        Integer max = state.getBudgetMax();
        if (min != null && max != null) {
            if (min > max) {
                return "";
            }
            return min + "-" + max + "元";
        }
        if (max != null) {
            return max + "元以内";
        }
        if (min != null) {
            return min + "元以上";
        }
        return "";
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String sanitized = value
                .replace((char) 0x2028, ' ')
                .replace((char) 0x2029, ' ')
                .replace((char) 0x0085, ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.length() > MAX_FIELD_LENGTH) {
            return sanitized.substring(0, MAX_FIELD_LENGTH);
        }
        return sanitized;
    }
}
