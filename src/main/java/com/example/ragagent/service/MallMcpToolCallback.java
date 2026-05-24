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

    MallMcpToolCallback(MallMcpClient mallMcpClient) {
        this.mallMcpClient = mallMcpClient;
        this.delegateProvider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(mallMcpClient.syncClient())
                .toolFilter((connectionInfo, tool) -> isMallTool(tool.name()))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build();
    }

    List<ToolCallback> getToolCallbacks() {
        mallMcpClient.ensureInitialized();
        return Arrays.stream(delegateProvider.getToolCallbacks())
                .map(callback -> (ToolCallback) new MallSessionToolCallback(callback))
                .toList();
    }

    static boolean isMallTool(String toolName) {
        return MallTool.isMallTool(toolName);
    }
}
