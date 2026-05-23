package com.example.ragagent.mall;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.mall.mcp")
public class MallMcpProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8120";

    @NotBlank
    private String endpoint = "/mcp";

    @NotBlank
    private String contextPath = "/internal/mcp/mall/context";

    @NotBlank
    private String contextSecret = "mall-mcp-dev-secret";

    @NotNull
    private Duration requestTimeout = Duration.ofSeconds(5);
}
