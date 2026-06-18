package com.example.ragagent.service;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

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

    @Override
    public String call(String input) {
        return renderer.render(toolName(), delegate.call(input));
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        return renderer.render(toolName(), delegate.call(input, toolContext));
    }

    private String toolName() {
        ToolDefinition definition = getToolDefinition();
        return definition == null ? "" : definition.name();
    }
}
