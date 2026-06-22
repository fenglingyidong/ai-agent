package com.example.ragagent.service;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * 包装商城工具回调，将原始 MCP 返回转换成模型更容易遵守的导购文本。
 */
final class MallRenderedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final MallToolResultRenderer renderer;

    MallRenderedToolCallback(ToolCallback delegate, MallToolResultRenderer renderer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 调用无上下文商城工具并渲染结果。
     */
    @Override
    public String call(String input) {
        return renderer.render(toolName(), delegate.call(input));
    }

    /**
     * 调用带 ToolContext 的商城工具并渲染结果。
     */
    @Override
    public String call(String input, ToolContext toolContext) {
        return renderer.render(toolName(), delegate.call(input, toolContext));
    }

    private String toolName() {
        ToolDefinition definition = getToolDefinition();
        return definition == null ? "" : definition.name();
    }
}
