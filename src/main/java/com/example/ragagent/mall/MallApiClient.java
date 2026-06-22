package com.example.ragagent.mall;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 调用商城后端认证接口，负责账号密码登录并解析登录令牌。
 */
@Component
public class MallApiClient {

    private static final Logger log = LoggerFactory.getLogger(MallApiClient.class);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MallProperties mallProperties;

    public MallApiClient() {
    }

    public MallApiClient(ObjectMapper objectMapper, MallProperties mallProperties) {
        this.objectMapper = objectMapper;
        this.mallProperties = mallProperties;
        configureTimeout();
    }

    /**
     * 初始化 RestTemplate 超时配置。
     */
    @PostConstruct
    public void init() {
        configureTimeout();
    }

    /**
     * 使用商城账号密码登录，成功时返回用户信息和令牌。
     */
    public Optional<LoginResponse> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Optional.empty();
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl())
                .path(mallProperties.getLoginPath())
                .build()
                .encode()
                .toUri();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<LoginRequest> entity = new HttpEntity<>(new LoginRequest(username.trim(), password), headers);
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.POST, entity, String.class);
            if (!StringUtils.hasText(response.getBody())) {
                log.warn("商城登录接口返回空响应：uri={}", uri);
                return Optional.empty();
            }
            JavaType wrapperType = objectMapper.getTypeFactory().constructParametricType(MallApiResponse.class, LoginResponse.class);
            MallApiResponse<LoginResponse> parsed = objectMapper.readValue(response.getBody(), wrapperType);
            if (parsed.code() != 0 || parsed.data() == null || !StringUtils.hasText(parsed.data().token())) {
                return Optional.empty();
            }
            return Optional.of(parsed.data());
        }
        catch (Exception ex) {
            log.warn("商城登录接口调用失败：uri={}, username={}, error={}", uri, username.trim(), ex.getMessage());
            return Optional.empty();
        }
    }

    private void configureTimeout() {
        Duration timeout = mallProperties == null ? DEFAULT_REQUEST_TIMEOUT : mallProperties.getRequestTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = DEFAULT_REQUEST_TIMEOUT;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        restTemplate.setRequestFactory(factory);
    }

    private String baseUrl() {
        return mallProperties.getBaseUrl().replaceAll("/+$", "");
    }

    /**
     * 商城通用响应包裹结构。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MallApiResponse<T>(int code, String message, T data) {
    }

    /**
     * 商城登录请求，toString 会隐藏密码。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginRequest(String username, String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=<masked>]";
        }
    }

    /**
     * 商城登录成功后的用户身份和令牌。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginResponse(String userId, String username, String token) {
    }
}
