package com.example.ragagent.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.stream.Collectors;

@Configuration
public class SpringAiLangfuseObservationConfig {

    private static final int DEFAULT_MAX_CAPTURE_CHARS = 8_000;

    @Bean
    BeanPostProcessor openAiChatModelObservationPostProcessor(ChatModelObservationConvention convention) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof OpenAiChatModel model) {
                    model.setObservationConvention(convention);
                }
                return bean;
            }
        };
    }

    @Bean
    ChatModelObservationConvention langfuseChatModelObservationConvention(LangfuseProperties properties,
                                                                          RagTracing tracing) {
        return new DefaultChatModelObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
                KeyValues values = defaultHighCardinalityKeyValues(context);
                if (context == null || properties == null || !properties.isEnabled() || !properties.isCapturePrompt()) {
                    return values;
                }

                KeyValues capturedValues = KeyValues.empty();
                boolean hasCapturedValues = false;
                String input = renderPrompt(context.getRequest(), properties.getMaxCaptureChars(), tracing);
                if (StringUtils.hasText(input)) {
                    capturedValues = capturedValues.and(
                            KeyValue.of("langfuse.observation.input", input),
                            KeyValue.of("gen_ai.prompt", input),
                            KeyValue.of("llm.spring_ai.input", input)
                    );
                    hasCapturedValues = true;
                }

                String output = renderCompletion(context.getResponse(), properties.getMaxCaptureChars(), tracing);
                if (StringUtils.hasText(output)) {
                    capturedValues = capturedValues.and(
                            KeyValue.of("langfuse.observation.output", output),
                            KeyValue.of("gen_ai.completion", output),
                            KeyValue.of("llm.spring_ai.output", output)
                    );
                    hasCapturedValues = true;
                }
                if (!hasCapturedValues) {
                    return values;
                }
                return values.and(capturedValues);
            }

            private KeyValues defaultHighCardinalityKeyValues(ChatModelObservationContext context) {
                try {
                    return super.getHighCardinalityKeyValues(context);
                }
                catch (RuntimeException ex) {
                    return KeyValues.empty();
                }
            }
        };
    }

    static String renderPrompt(Prompt prompt, int maxChars, RagTracing tracing) {
        if (prompt == null || prompt.getInstructions() == null || prompt.getInstructions().isEmpty()) {
            return "";
        }

        String rendered = prompt.getInstructions().stream()
                .map(SpringAiLangfuseObservationConfig::renderMessage)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        if (!StringUtils.hasText(rendered)) {
            return "";
        }

        String sanitized = tracing == null ? rendered : tracing.sanitizePromptText(rendered);
        int limit = maxChars > 0 ? maxChars : DEFAULT_MAX_CAPTURE_CHARS;
        return sanitized.length() > limit ? sanitized.substring(0, limit) : sanitized;
    }

    static String renderCompletion(ChatResponse response, int maxChars, RagTracing tracing) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "";
        }

        String rendered = response.getResults().stream()
                .map(SpringAiLangfuseObservationConfig::renderGeneration)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        if (!StringUtils.hasText(rendered)) {
            return "";
        }

        String sanitized = tracing == null ? rendered : tracing.sanitizePromptText(rendered);
        int limit = maxChars > 0 ? maxChars : DEFAULT_MAX_CAPTURE_CHARS;
        return sanitized.length() > limit ? sanitized.substring(0, limit) : sanitized;
    }

    private static String renderGeneration(Generation generation) {
        if (generation == null || generation.getOutput() == null
                || !StringUtils.hasText(generation.getOutput().getText())) {
            return "";
        }
        return generation.getOutput().getText();
    }

    private static String renderMessage(Message message) {
        if (message == null) {
            return "";
        }
        String role = message.getMessageType() == null
                ? "message"
                : message.getMessageType().name().toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(role).append(":");
        String text = message.getText();
        if (StringUtils.hasText(text)) {
            builder.append(System.lineSeparator()).append(text);
        }

        if (message instanceof UserMessage userMessage && !userMessage.getMedia().isEmpty()) {
            builder.append(System.lineSeparator())
                    .append("media_count: ")
                    .append(userMessage.getMedia().size());
        }
        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            builder.append(System.lineSeparator())
                    .append("tool_calls: ")
                    .append(assistantMessage.getToolCalls());
        }
        if (message instanceof ToolResponseMessage toolResponseMessage
                && !toolResponseMessage.getResponses().isEmpty()) {
            builder.append(System.lineSeparator())
                    .append("tool_responses: ")
                    .append(toolResponseMessage.getResponses());
        }
        return builder.toString();
    }
}
