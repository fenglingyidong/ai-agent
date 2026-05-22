package com.example.ragagent.mall;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.mall.mcp")
public class MallMcpProperties {

    private String baseUrl = "http://localhost:8120";
    private String endpoint = "/mcp";
    private String contextPath = "/internal/mcp/mall/context";
    private String contextSecret = "mall-mcp-dev-secret";
    private Duration requestTimeout = Duration.ofSeconds(5);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getContextPath() {
        return contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public String getContextSecret() {
        return contextSecret;
    }

    public void setContextSecret(String contextSecret) {
        this.contextSecret = contextSecret;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
