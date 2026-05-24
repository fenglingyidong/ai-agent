package com.example.ragagent.commerce;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingPreferencePromptRendererTest {

    private final ShoppingPreferencePromptRenderer renderer = new ShoppingPreferencePromptRenderer();

    @Test
    void renderShouldReturnEmptyWhenNoPreferenceExists() {
        assertEquals("", renderer.render(new ShoppingPreferenceState()));
    }

    @Test
    void renderShouldCompressKnownPreference() {
        ShoppingPreferenceState state = new ShoppingPreferenceState();
        state.setCategory("跑鞋");
        state.setBudgetMax(500);
        state.setBrand("Nike");
        state.setUsageScenario("通勤");

        String prompt = renderer.render(state);

        assertTrue(prompt.contains("当前会话短期导购偏好"));
        assertTrue(prompt.contains("品类：跑鞋"));
        assertTrue(prompt.contains("预算：500以内"));
        assertTrue(prompt.contains("品牌：Nike"));
        assertTrue(prompt.contains("使用场景：通勤"));
        assertTrue(prompt.contains("如果用户本轮明确修改偏好，以本轮为准"));
    }
}
