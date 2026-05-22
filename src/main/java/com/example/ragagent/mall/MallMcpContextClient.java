package com.example.ragagent.mall;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;

@Component
public class MallMcpContextClient {

    private static final Logger log = LoggerFactory.getLogger(MallMcpContextClient.class);
    private static final String CONTEXT_SECRET_HEADER = "X-Mcp-Context-Secret";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MallMcpProperties properties;

    public MallMcpContextClient(ObjectMapper objectMapper, MallMcpProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restTemplate = new RestTemplate();
        Duration timeout = properties.getRequestTimeout();
        if (timeout != null) {
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
            this.restTemplate.setRequestFactory(factory);
        }
    }

    public MallMcpContextRegistration register(String userId,
                                               String sessionId,
                                               String mallToken,
                                               String mallUsername,
                                               String mallPassword) {
        if (!StringUtils.hasText(sessionId)) {
            return MallMcpContextRegistration.failed("sessionId is required");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(CONTEXT_SECRET_HEADER, properties.getContextSecret());
            MallMcpContextRequest body = new MallMcpContextRequest(
                    sessionId.trim(),
                    trimToBlank(userId),
                    trimToBlank(mallToken),
                    trimToBlank(mallUsername),
                    nullToBlank(mallPassword)
            );
            ResponseEntity<String> response = restTemplate.exchange(
                    contextUri(),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                return MallMcpContextRegistration.failed("context api status=" + response.getStatusCode());
            }
            if (!StringUtils.hasText(response.getBody())) {
                return MallMcpContextRegistration.success();
            }
            MallMcpToolEnvelope parsed = objectMapper.readValue(response.getBody(), MallMcpToolEnvelope.class);
            if (!parsed.ok()) {
                return MallMcpContextRegistration.failed(parsed.message());
            }
            return MallMcpContextRegistration.success();
        }
        catch (ResourceAccessException ex) {
            log.warn("mall-mcp 上下文注册失败：无法连接 mall-mcp，sessionId={}, error={}", sessionId, ex.getMessage());
            return MallMcpContextRegistration.failed("mall-mcp 服务未启动或不可访问");
        }
        catch (Exception ex) {
            log.warn("mall-mcp 上下文注册失败：sessionId={}, error={}", sessionId, ex.getMessage());
            return MallMcpContextRegistration.failed(ex.getMessage());
        }
    }

    private URI contextUri() {
        String path = StringUtils.hasText(properties.getContextPath())
                ? properties.getContextPath().trim()
                : "/internal/mcp/mall/context";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getBaseUrl()))
                .path(path)
                .build()
                .encode()
                .toUri();
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "http://localhost:8120";
        }
        return value.trim().replaceAll("/+$", "");
    }

    private String trimToBlank(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public record MallMcpContextRegistration(boolean ok, String message) {

        public static MallMcpContextRegistration success() {
            return new MallMcpContextRegistration(true, "success");
        }

        public static MallMcpContextRegistration failed(String message) {
            return new MallMcpContextRegistration(false, StringUtils.hasText(message) ? message : "mall-mcp context failed");
        }
    }

    private record MallMcpContextRequest(
            String sessionId,
            String userId,
            String mallToken,
            String mallUsername,
            String mallPassword
    ) {

        @Override
        public String toString() {
            return "MallMcpContextRequest[sessionId=" + sessionId
                    + ", userId=" + userId
                    + ", mallToken=<masked>"
                    + ", mallUsername=" + mallUsername
                    + ", mallPassword=<masked>]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MallMcpToolEnvelope(boolean ok, String code, String message) {
    }
}
