package com.example.ragagent.mall;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 商城后端 API 配置，包括基础地址、登录路径、鉴权头和缓存时间。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.mall")
public class MallProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8100";

    @NotBlank
    private String authorizationHeader = "X-Mall-Authorization";

    @NotBlank
    private String loginPath = "/api/auth/login";

    @NotNull
    private Duration requestTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration authCacheTtl = Duration.ofHours(2);

}
