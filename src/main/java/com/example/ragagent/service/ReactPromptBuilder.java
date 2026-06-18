package com.example.ragagent.service;

import com.example.ragagent.prompt.PromptTemplateStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReactPromptBuilder {

    private static final String REACT_SYSTEM_PROMPT = "react.system";
    private static final String REACT_MALL_RULE_ENABLED = "react.mall-rule.enabled";
    private static final String REACT_MALL_RULE_DISABLED = "react.mall-rule.disabled";
    private static final String REACT_NETWORK_RULE_ENABLED = "react.network-rule.enabled";
    private static final String REACT_NETWORK_RULE_DISABLED = "react.network-rule.disabled";
    private static final String REACT_TASK_POLICY_HEADER = "react.task-policy.header";
    private static final String REACT_TASK_POLICY_ITEM = "react.task-policy.item";
    private static final String REACT_TASK_POLICY_ALLOWED_TOOLS = "react.task-policy.allowed-tools";
    private static final String REACT_TASK_POLICY_CONFIRMATION_REQUIRED = "react.task-policy.confirmation-required";

    private final PromptTemplateStore promptTemplateStore;

    ReactPromptBuilder(PromptTemplateStore promptTemplateStore) {
        this.promptTemplateStore = promptTemplateStore == null ? new PromptTemplateStore() : promptTemplateStore;
    }

    String buildSystemPrompt(boolean hasExternalTools,
                             boolean hasMallTools,
                             boolean webSearchEnabled,
                             List<ShoppingTaskPolicy> taskPolicies,
                             Set<String> activeToolNames,
                             String trustedContext) {
        String systemPrompt = renderSystemPrompt(
                hasExternalTools,
                hasMallTools,
                webSearchEnabled,
                taskPolicies,
                activeToolNames
        );
        return withTrustedSystemContext(systemPrompt, trustedContext);
    }

    String reactInput(Prompt prompt, List<String> toolNames) {
        StringBuilder builder = new StringBuilder();
        List<Message> messages = prompt == null ? List.of() : prompt.getInstructions();
        for (Message message : messages) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append(messageRole(message)).append(":").append(System.lineSeparator())
                    .append(messageText(message));
            if (message instanceof UserMessage) {
                builder.append(System.lineSeparator())
                        .append("media_count: ")
                        .append(mediaCountFromMessage(message));
            }
        }
        builder.append(System.lineSeparator())
                .append("tools: ")
                .append(toolNames == null ? List.of() : toolNames);
        return builder.toString();
    }

    private String renderSystemPrompt(boolean hasExternalTools,
                                      boolean hasMallTools,
                                      boolean webSearchEnabled,
                                      List<ShoppingTaskPolicy> taskPolicies,
                                      Set<String> activeToolNames) {
        String mallRule = hasMallTools
                ? promptTemplateStore.text(REACT_MALL_RULE_ENABLED)
                : promptTemplateStore.text(REACT_MALL_RULE_DISABLED);
        String networkRule = webSearchEnabled && hasExternalTools
                ? promptTemplateStore.text(REACT_NETWORK_RULE_ENABLED)
                : promptTemplateStore.text(REACT_NETWORK_RULE_DISABLED);
        String policyPrompt = renderTaskPolicyPrompt(taskPolicies, activeToolNames);
        return promptTemplateStore.render(REACT_SYSTEM_PROMPT, Map.of(
                "mall_rule", mallRule,
                "network_rule", networkRule,
                "task_policy_prompt", StringUtils.hasText(policyPrompt)
                        ? System.lineSeparator() + System.lineSeparator() + policyPrompt
                        : ""
        ));
    }

    private String withTrustedSystemContext(String systemPrompt, String trustedContext) {
        if (!StringUtils.hasText(trustedContext)) {
            return systemPrompt;
        }
        return systemPrompt
                + System.lineSeparator()
                + System.lineSeparator()
                + trustedContext.trim();
    }

    private String messageRole(Message message) {
        if (message == null || message.getMessageType() == null) {
            return "message";
        }
        return message.getMessageType().name().toLowerCase(java.util.Locale.ROOT);
    }

    private String messageText(Message message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.getText();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.getText();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return message == null ? "" : String.valueOf(message);
    }

    private int mediaCountFromMessage(Message message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.getMedia().size();
        }
        return 0;
    }

    private String renderTaskPolicyPrompt(List<ShoppingTaskPolicy> taskPolicies, Set<String> activeToolNames) {
        if (taskPolicies == null || taskPolicies.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(promptTemplateStore.text(REACT_TASK_POLICY_HEADER));
        boolean hasRenderablePolicy = false;
        for (ShoppingTaskPolicy policy : taskPolicies) {
            if (!isRenderableTaskPolicy(policy)) {
                continue;
            }
            builder.append(System.lineSeparator())
                    .append(renderTaskPolicyItem(policy, activeToolNames));
            hasRenderablePolicy = true;
        }
        return hasRenderablePolicy ? builder.toString() : "";
    }

    private boolean isRenderableTaskPolicy(ShoppingTaskPolicy policy) {
        return policy != null
                && (StringUtils.hasText(policy.promptFragment())
                || !policy.allowedToolNames().isEmpty()
                || policy.confirmationRequired());
    }

    private String renderTaskPolicyItem(ShoppingTaskPolicy policy, Set<String> activeToolNames) {
        return promptTemplateStore.render(REACT_TASK_POLICY_ITEM, Map.of(
                "policy_id", policy.id(),
                "policy_name", policy.name(),
                "policy_prompt", policy.promptFragment(),
                "allowed_tools_text", renderAllowedToolsText(policy, activeToolNames),
                "confirmation_required_text", renderConfirmationRequiredText(policy)
        ));
    }

    private String renderAllowedToolsText(ShoppingTaskPolicy policy, Set<String> activeToolNames) {
        if (policy.allowedToolNames().isEmpty()) {
            return "";
        }
        Set<String> renderedToolNames = visibleAllowedToolNames(policy, activeToolNames);
        if (renderedToolNames.isEmpty()) {
            return "";
        }
        return promptTemplateStore.render(REACT_TASK_POLICY_ALLOWED_TOOLS, Map.of(
                "allowed_tools", String.join(", ", renderedToolNames)
        ));
    }

    private Set<String> visibleAllowedToolNames(ShoppingTaskPolicy policy, Set<String> activeToolNames) {
        if (activeToolNames == null) {
            return policy.allowedToolNames();
        }
        if (activeToolNames.isEmpty()) {
            return Set.of();
        }
        return policy.allowedToolNames().stream()
                .filter(activeToolNames::contains)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private String renderConfirmationRequiredText(ShoppingTaskPolicy policy) {
        return policy.confirmationRequired()
                ? promptTemplateStore.text(REACT_TASK_POLICY_CONFIRMATION_REQUIRED)
                : "";
    }
}
