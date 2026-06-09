package com.example.ragagent.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class PromptTemplateStore {

    private static final String DEFAULT_BASE_PATH = "prompts";

    private final String basePath;

    public PromptTemplateStore() {
        this(DEFAULT_BASE_PATH);
    }

    private PromptTemplateStore(String basePath) {
        this.basePath = normalizeBasePath(basePath);
    }

    public static PromptTemplateStore fromClasspath(String basePath) {
        return new PromptTemplateStore(basePath);
    }

    public String render(String name) {
        return text(name);
    }

    public String text(String name) {
        Resource resource = new ClassPathResource(basePath + "/" + normalizeName(name) + ".st");
        return trimTrailingLineBreaks(readUtf8(resource));
    }

    public String render(String name, Map<String, Object> variables) {
        String template = text(name);
        String rendered = new PromptTemplate(template).render(variables == null ? Map.of() : variables);
        return trimTrailingLineBreaks(rendered);
    }

    private String readUtf8(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("failed to load prompt template: " + resource, ex);
        }
    }

    private String normalizeBasePath(String value) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT_BASE_PATH;
        }
        return trimSlashes(value.trim());
    }

    private String normalizeName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("prompt name must not be blank");
        }
        String name = trimSlashes(value.trim());
        if (name.endsWith(".st")) {
            return name.substring(0, name.length() - 3);
        }
        return name;
    }

    private String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String trimTrailingLineBreaks(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int end = value.length();
        while (end > 0) {
            char character = value.charAt(end - 1);
            if (character != '\n' && character != '\r') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }
}
