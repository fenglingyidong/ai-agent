package com.example.ragagent.config;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI MCP 客户端配置，统一工具命名和可传递给 MCP meta 的上下文字段。
 */
@Configuration
public class McpClientConfiguration {

    private static final String[] MCP_META_KEYS = {
            "userId",
            "sessionId",
            "mallToken",
            "mallUsername",
            "mallPassword"
    };

    /**
     * 保持 MCP 服务端原始工具名，避免 mall_* 和 WebSearch 工具名被客户端名前缀改写。
     */
    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }

    /**
     * 只把白名单上下文字段写入 MCP CallToolRequest.meta，避免泄露本地缓存或非序列化对象。
     */
    @Bean
    public ToolContextToMcpMetaConverter toolContextToMcpMetaConverter() {
        return this::toMcpMeta;
    }

    private Map<String, Object> toMcpMeta(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null || toolContext.getContext().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        for (String key : MCP_META_KEYS) {
            Object value = toolContext.getContext().get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                meta.put(key, value.toString().trim());
            }
        }
        return meta.isEmpty() ? Map.of() : Map.copyOf(meta);
    }
}
