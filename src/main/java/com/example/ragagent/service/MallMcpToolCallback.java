package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpClient;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;

final class MallMcpToolCallback {

    private final MallMcpClient mallMcpClient;
    private final SyncMcpToolCallbackProvider delegateProvider;
    private final MallToolResultRenderer resultRenderer;

    MallMcpToolCallback(MallMcpClient mallMcpClient) {
        this(mallMcpClient, new MallToolResultRenderer());
    }

    MallMcpToolCallback(MallMcpClient mallMcpClient, MallToolResultRenderer resultRenderer) {
        this.mallMcpClient = mallMcpClient;
        this.resultRenderer = resultRenderer;
        this.delegateProvider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(mallMcpClient.syncClient())
                .toolFilter((connectionInfo, tool) -> isMallTool(tool.name()))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build();
    }

    List<ToolCallback> getToolCallbacks() {
        mallMcpClient.ensureInitialized();
        return Arrays.stream(delegateProvider.getToolCallbacks())
                .map(this::renderedSessionCallback)
                .toList();
    }

    private ToolCallback renderedSessionCallback(ToolCallback callback) {
        return new MallRenderedToolCallback(new MallSessionToolCallback(callback), resultRenderer);
    }

    static boolean isMallTool(String toolName) {
        return MallTool.isMallTool(toolName);
    }
}
