package com.example.ragagent.service;

import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.tools.BuiltInTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReactToolResolver {

    private static final Logger log = LoggerFactory.getLogger(ReactToolResolver.class);

    private final List<ToolCallback> builtInToolCallbacks;
    private final List<ToolCallbackProvider> externalToolCallbackProviders;
    private final MallMcpToolCallback mallMcpToolCallback;
    private final RagTracing tracing;

    ReactToolResolver(BuiltInTools builtInTools,
                      List<ToolCallbackProvider> externalToolCallbackProviders,
                      MallMcpToolCallback mallMcpToolCallback,
                      RagTracing tracing) {
        this.builtInToolCallbacks = loadBuiltInToolCallbacks(builtInTools);
        this.externalToolCallbackProviders = externalToolCallbackProviders == null
                ? List.of()
                : List.copyOf(externalToolCallbackProviders);
        this.mallMcpToolCallback = mallMcpToolCallback;
        this.tracing = tracing == null ? new RagTracing() : tracing;
    }

    ActiveToolCallbacks resolve(String userId,
                                String sessionId,
                                boolean webSearchEnabled,
                                boolean mallToolsAllowed,
                                List<ShoppingTaskPolicy> taskPolicies,
                                boolean orderCreationAllowed) {
        Map<String, ToolCallback> activeToolCallbacks = new LinkedHashMap<>();
        builtInToolCallbacks.forEach(callback -> putToolCallback(
                activeToolCallbacks,
                callback,
                userId,
                sessionId,
                orderCreationAllowed
        ));

        if (allowsMallTools(mallToolsAllowed, taskPolicies) && mallMcpToolCallback != null) {
            try {
                List<ToolCallback> mallToolCallbacks = mallMcpToolCallback.getToolCallbacks();
                if (mallToolCallbacks.isEmpty()) {
                    throw new MallMcpToolResolutionException("未发现 mall_* MCP 工具");
                }
                for (ToolCallback callback : mallToolCallbacks) {
                    putToolCallback(activeToolCallbacks, callback, userId, sessionId, orderCreationAllowed);
                }
            }
            catch (RuntimeException ex) {
                throw new MallMcpToolResolutionException(safeMallMcpMessage(ex), ex);
            }
        }

        if (!webSearchEnabled) {
            List<ToolCallback> callbacks = filterCallbacksByTaskPolicies(
                    List.copyOf(activeToolCallbacks.values()),
                    taskPolicies,
                    orderCreationAllowed
            );
            return new ActiveToolCallbacks(callbacks, false, hasMallTools(callbacks));
        }

        boolean hasExternalTools = false;
        for (ToolCallbackProvider provider : externalToolCallbackProviders) {
            try {
                ToolCallback[] callbacks = provider.getToolCallbacks();
                if (callbacks == null) {
                    continue;
                }

                for (ToolCallback callback : callbacks) {
                    String toolName = toolName(callback);
                    if (!StringUtils.hasText(toolName) || activeToolCallbacks.containsKey(toolName)) {
                        continue;
                    }
                    if (MallMcpToolCallback.isMallTool(toolName)) {
                        continue;
                    }
                    putToolCallback(activeToolCallbacks, callback, userId, sessionId, orderCreationAllowed);
                    hasExternalTools = true;
                }
            }
            catch (RuntimeException ex) {
                log.warn("Failed to resolve MCP tool callbacks, MCP tools will be skipped for this request", ex);
            }
        }

        List<ToolCallback> callbacks = filterCallbacksByTaskPolicies(
                List.copyOf(activeToolCallbacks.values()),
                taskPolicies,
                orderCreationAllowed
        );
        return new ActiveToolCallbacks(callbacks, hasExternalTools, hasMallTools(callbacks));
    }

    String mallMcpFailureMessage(MallMcpToolResolutionException ex) {
        String message = safeMallMcpMessage(ex);
        return message.startsWith("商城 MCP 调用失败：") ? message : "商城 MCP 调用失败：" + message;
    }

    private List<ToolCallback> loadBuiltInToolCallbacks(BuiltInTools builtInTools) {
        if (builtInTools == null) {
            return List.of();
        }
        return List.of(ToolCallbacks.from(builtInTools));
    }

    private List<ToolCallback> filterCallbacksByTaskPolicies(List<ToolCallback> callbacks,
                                                             List<ShoppingTaskPolicy> taskPolicies,
                                                             boolean orderCreationAllowed) {
        if (callbacks == null || callbacks.isEmpty()) {
            return List.of();
        }

        List<ShoppingTaskPolicy> safeTaskPolicies = taskPolicies == null ? List.of() : taskPolicies;
        Set<String> allowedToolNames = safeTaskPolicies.stream()
                .flatMap(policy -> policy.allowedToolNames().stream())
                .collect(java.util.stream.Collectors.toSet());

        return callbacks.stream()
                .filter(callback -> {
                    String name = toolName(callback);
                    if (MallTool.CREATE_ORDER.toolName().equals(name) && !orderCreationAllowed) {
                        return false;
                    }
                    return safeTaskPolicies.isEmpty()
                            || allowedToolNames.isEmpty()
                            || !isShoppingControlledTool(name)
                            || allowedToolNames.contains(name);
                })
                .toList();
    }

    private boolean allowsMallTools(boolean mallToolsAllowed, List<ShoppingTaskPolicy> taskPolicies) {
        if (!mallToolsAllowed) {
            return false;
        }
        Set<String> allowedToolNames = allowedToolNames(taskPolicies);
        return allowedToolNames.isEmpty() || allowedToolNames.stream().anyMatch(MallMcpToolCallback::isMallTool);
    }

    private boolean isShoppingControlledTool(String toolName) {
        return "searchProductKnowledge".equals(toolName)
                || MallMcpToolCallback.isMallTool(toolName);
    }

    private boolean hasMallTools(List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return false;
        }
        return callbacks.stream()
                .map(this::toolName)
                .anyMatch(MallMcpToolCallback::isMallTool);
    }

    private Set<String> allowedToolNames(List<ShoppingTaskPolicy> taskPolicies) {
        if (taskPolicies == null || taskPolicies.isEmpty()) {
            return Set.of();
        }
        return taskPolicies.stream()
                .flatMap(policy -> policy.allowedToolNames().stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    private void putToolCallback(Map<String, ToolCallback> activeToolCallbacks,
                                 ToolCallback callback,
                                 String userId,
                                 String sessionId,
                                 boolean orderCreationAllowed) {
        String toolName = toolName(callback);
        if (StringUtils.hasText(toolName)) {
            ToolCallback guardedCallback = MallTool.CREATE_ORDER.toolName().equals(toolName)
                    ? new OrderCreationGuardedToolCallback(callback, orderCreationAllowed)
                    : callback;
            activeToolCallbacks.put(toolName, new LoggingToolCallback(guardedCallback, userId, sessionId, tracing));
        }
    }

    private String toolName(ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null) {
            return "";
        }
        return callback.getToolDefinition().name();
    }

    private String safeMallMcpMessage(RuntimeException ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return "mall-mcp 服务未启动或不可访问";
        }
        return ex.getMessage().trim();
    }

    record ActiveToolCallbacks(List<ToolCallback> callbacks, boolean hasExternalTools, boolean hasMallTools) {

        List<String> toolNames() {
            if (callbacks == null || callbacks.isEmpty()) {
                return List.of();
            }
            return callbacks.stream()
                    .filter(callback -> callback != null && callback.getToolDefinition() != null)
                    .map(callback -> callback.getToolDefinition().name())
                    .filter(StringUtils::hasText)
                    .toList();
        }

        Set<String> toolNameSet() {
            if (callbacks == null || callbacks.isEmpty()) {
                return Set.of();
            }
            return callbacks.stream()
                    .filter(callback -> callback != null && callback.getToolDefinition() != null)
                    .map(callback -> callback.getToolDefinition().name())
                    .filter(StringUtils::hasText)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
    }

    static final class MallMcpToolResolutionException extends RuntimeException {

        private MallMcpToolResolutionException(String message) {
            super(StringUtils.hasText(message) ? message : "mall-mcp 服务未启动或不可访问");
        }

        private MallMcpToolResolutionException(String message, Throwable cause) {
            super(StringUtils.hasText(message) ? message : "mall-mcp 服务未启动或不可访问", cause);
        }
    }
}
