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

/**
 * 统一从 classpath 的 prompts 目录加载 UTF-8 模板，并提供简单变量渲染能力。
 */
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

    /**
     * 读取不带变量的模板文本，语义上作为渲染入口使用。
     */
    public String render(String name) {
        return text(name);
    }

    /**
     * 按模板名称读取原始模板文本，并去掉末尾多余换行。
     */
    public String text(String name) {
        Resource resource = new ClassPathResource(basePath + "/" + normalizeName(name) + ".st");
        return trimTrailingLineBreaks(readUtf8(resource));
    }

    /**
     * 使用 Spring AI PromptTemplate 渲染指定模板。
     */
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
