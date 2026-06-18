package com.example.ragagent.service;

import com.example.ragagent.prompt.PromptTemplateStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactPromptBuilderTest {

    private final ReactPromptBuilder builder = new ReactPromptBuilder(new PromptTemplateStore());

    @Test
    void buildSystemPromptShouldRenderRulesPoliciesAndTrustedContext() {
        ShoppingTaskPolicy policy = new ShoppingTaskPolicy(
                "RECOMMENDATION",
                "推荐",
                Set.of("RECOMMENDATION"),
                Set.of("budget"),
                Set.of("searchProductKnowledge", "mall_search_products"),
                false,
                "预算明确时先给出候选商品。"
        );

        String prompt = builder.buildSystemPrompt(
                true,
                true,
                true,
                List.of(policy),
                Set.of("searchProductKnowledge"),
                "[可信上下文] 当前预算 700 元。"
        );

        assertTrue(prompt.contains("核心规则"));
        assertTrue(prompt.contains("RECOMMENDATION / 推荐"));
        assertTrue(prompt.contains("预算明确时先给出候选商品。"));
        assertTrue(prompt.contains("受控工具范围：searchProductKnowledge。"));
        assertTrue(prompt.endsWith("[可信上下文] 当前预算 700 元。"));
    }

    @Test
    void reactInputShouldFormatPromptMessagesAndToolNamesForTracing() {
        Prompt prompt = Prompt.builder()
                .messages(List.of(
                        new SystemMessage("系统规则"),
                        new UserMessage("推荐跑鞋")
                ))
                .build();

        String input = builder.reactInput(prompt, List.of("searchProductKnowledge"));

        assertTrue(input.contains("system:" + System.lineSeparator() + "系统规则"));
        assertTrue(input.contains("user:" + System.lineSeparator() + "推荐跑鞋"));
        assertTrue(input.contains("media_count: 0"));
        assertTrue(input.endsWith("tools: [searchProductKnowledge]"));
    }
}
