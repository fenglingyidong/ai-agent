package com.example.ragagent.mall;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;

@Component
public class MallApiClient {

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
    }

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
            return Optional.empty();
        }
    }

    private String baseUrl() {
        return mallProperties.getBaseUrl().replaceAll("/+$", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MallApiResponse<T>(int code, String message, T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginRequest(String username, String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=<masked>]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginResponse(String userId, String username, String token) {
    }
}
