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

        assertTrue(text.contains("{\"task_type\": \"C_COMPLEX_REACT\"}"));
    }
}
