package com.example.ragagent.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateStoreTest {

    @Test
    void shouldLoadPromptSkillFragmentsFromNestedDirectory() {
        PromptTemplateStore store = new PromptTemplateStore();

        String text = store.text("skills/recommendation");
        String unknownText = store.text("skills/unknown");

        assertTrue(text.contains("推荐任务"));
        assertTrue(text.contains("不得请求用户先补充信息"));
        assertEquals("前置路由未能识别用户意图，请自行判断。", unknownText);
    }

    @Test
    void productKnowledgePromptsShouldTreatExplicitGuideTextAsFacts() {
        PromptTemplateStore store = new PromptTemplateStore();

        String reactPrompt = store.render("react.system", Map.of(
                "mall_rule", "mall rule",
                "network_rule", "network rule",
                "task_policy_prompt", ""
        ));
        String simpleKnowledgePrompt = store.text("simple-task.knowledge.system");

        assertTrue(reactPrompt.contains("核心规则"));
        assertTrue(reactPrompt.contains("原文场景/人群/建议当事实"));
        assertTrue(reactPrompt.contains("mall rule"));
        assertTrue(reactPrompt.contains("network rule"));
        assertTrue(reactPrompt.contains("工具调用完成前不要输出可见文字"));
        assertTrue(!reactPrompt.contains("searchProductKnowledge"));
        assertTrue(!reactPrompt.contains("updateShoppingPreference"));
        assertTrue(simpleKnowledgePrompt.contains("事实边界"));
        assertTrue(simpleKnowledgePrompt.contains("知识库原文事实"));
        assertTrue(simpleKnowledgePrompt.contains("纯中文文本"));
    }
}
