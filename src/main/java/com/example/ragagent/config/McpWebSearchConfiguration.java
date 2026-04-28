package com.example.ragagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientRequest;

@Configuration
public class McpWebSearchConfiguration {

    private static final String DASHSCOPE_HOST = "dashscope.aliyuncs.com";
    private static final String WEBSEARCH_MCP_PATH = "/api/v1/mcps/WebSearch/mcp";

    @Bean
    public WebClientCustomizer mcpWebSearchAuthorizationCustomizer(
            @Value("${app.mcp.websearch.api-key:}") String apiKey) {
        return builder -> builder.filter((request, next) -> {
            if (!shouldAttachAuthorization(request, apiKey)) {
                return next.exchange(request);
            }

            ClientRequest authorizedRequest = ClientRequest.from(request)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .build();
            return next.exchange(authorizedRequest);
        });
    }

    private boolean shouldAttachAuthorization(ClientRequest request, String apiKey) {
        return StringUtils.hasText(apiKey)
                && DASHSCOPE_HOST.equalsIgnoreCase(request.url().getHost())
                && request.url().getPath() != null
                && request.url().getPath().startsWith(WEBSEARCH_MCP_PATH)
                && !request.headers().containsKey(HttpHeaders.AUTHORIZATION);
    }
}
