package com.example.ragagent.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiLangfuseObservationConfigTest {

    @Test
    void conventionShouldNotCapturePromptWhenDisabled() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(false);
        ChatModelObservationConvention convention = new SpringAiLangfuseObservationConfig()
                .langfuseChatModelObservationConvention(properties, new RagTracing(properties));

        KeyValues values = convention.getHighCardinalityKeyValues(context(new Prompt("hello")));

        assertFalse(containsKey(values, "langfuse.observation.input"));
    }

    @Test
    void conventionShouldWriteLangfuseInputWhenCaptureEnabled() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(true);
        ChatModelObservationConvention convention = new SpringAiLangfuseObservationConfig()
                .langfuseChatModelObservationConvention(properties, new RagTracing(properties));
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("system rules"),
                new UserMessage("user asks"),
                new AssistantMessage("assistant draft")
        ));

        KeyValues values = convention.getHighCardinalityKeyValues(context(prompt));

        String input = value(values, "langfuse.observation.input");
        assertTrue(input.contains("system:" + System.lineSeparator() + "system rules"));
        assertTrue(input.contains("user:" + System.lineSeparator() + "user asks"));
        assertTrue(input.contains("assistant:" + System.lineSeparator() + "assistant draft"));
        assertTrue(input.equals(value(values, "llm.spring_ai.input")));
        assertTrue(input.equals(value(values, "gen_ai.prompt")));
    }

    @Test
    void conventionShouldWriteLangfuseOutputWhenCaptureEnabled() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(true);
        ChatModelObservationConvention convention = new SpringAiLangfuseObservationConfig()
                .langfuseChatModelObservationConvention(properties, new RagTracing(properties));
        ChatModelObservationContext context = context(new Prompt("hello"));
        context.setResponse(new ChatResponse(List.of(
                new Generation(new AssistantMessage("assistant answer"))
        )));

        KeyValues values = convention.getHighCardinalityKeyValues(context);

        String output = value(values, "langfuse.observation.output");
        assertTrue(output.contains("assistant answer"));
        assertTrue(output.equals(value(values, "llm.spring_ai.output")));
        assertTrue(output.equals(value(values, "gen_ai.completion")));
    }

    @Test
    void renderPromptShouldSanitizeAndTruncate() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(true);
        String rendered = SpringAiLangfuseObservationConfig.renderPrompt(
                new Prompt(new UserMessage("token=abc123 " + "长".repeat(50))),
                30,
                new RagTracing(properties)
        );

        assertFalse(rendered.contains("abc123"));
        assertTrue(rendered.length() <= 30);
    }

    private ChatModelObservationContext context(Prompt prompt) {
        return ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("openai")
                .build();
    }

    private boolean containsKey(KeyValues values, String key) {
        return values.stream().anyMatch(value -> value.getKey().equals(key));
    }

    private String value(KeyValues values, String key) {
        return values.stream()
                .filter(value -> value.getKey().equals(key))
                .findFirst()
                .map(KeyValue::getValue)
                .orElse("");
    }
}
