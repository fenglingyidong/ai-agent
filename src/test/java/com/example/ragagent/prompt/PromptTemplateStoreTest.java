package com.example.ragagent.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateStoreTest {

    @Test
    void shouldRenderClasspathPromptTemplateWithVariables() {
        PromptTemplateStore store = PromptTemplateStore.fromClasspath("prompts/test");

        String rendered = store.render("sample", Map.of(
                "name", "Alice",
                "task", "推荐跑鞋"
        ));

        assertEquals("你好 Alice，请执行：推荐跑鞋。", rendered);
    }

    @Test
    void shouldTrimTrailingWhitespaceLoadedFromPromptFile() {
        PromptTemplateStore store = PromptTemplateStore.fromClasspath("prompts/test");

        String rendered = store.render("sample", Map.of(
                "name", "Bob",
                "task", "查看购物车"
        ));

        assertTrue(!rendered.endsWith("\n"));
        assertTrue(!rendered.endsWith("\r"));
    }

    @Test
    void shouldLoadRawPromptTextWithoutRenderingJsonBraces() {
        PromptTemplateStore store = PromptTemplateStore.fromClasspath("prompts/test");

        String text = store.text("json");

        assertTrue(text.contains("{\"task_type\": \"COMPLEX_REACT\"}"));
    }

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

        assertTrue(reactPrompt.contains("原文场景/人群/建议当事实"));
        assertTrue(reactPrompt.contains("其余建议标为“导购推断”"));
        assertTrue(reactPrompt.contains("唯一可推荐商品池"));
        assertTrue(reactPrompt.contains("为什么这么回答"));
        assertTrue(!reactPrompt.contains("searchProductKnowledge"));
        assertTrue(!reactPrompt.contains("updateShoppingPreference"));
        assertTrue(simpleKnowledgePrompt.contains("知识库原文或元数据明确写出"));
        assertTrue(simpleKnowledgePrompt.contains("导购说明原文已经写出适用人群、使用场景或购买建议时"));
    }
}
