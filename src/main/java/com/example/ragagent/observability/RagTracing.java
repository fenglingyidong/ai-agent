package com.example.ragagent.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * 封装 RAG 和 agent 链路的 OpenTelemetry/Langfuse 埋点、脱敏和调试内容采集。
 */
@Component
public class RagTracing {

    private static final int PROMPT_CAPTURE_MAX_CHARS = 8_000;
    private static final int DEBUG_DOCUMENT_TEXT_MAX_CHARS = 2_000;
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|secret|api[_-]?key)\\b(\\s*[:=]\\s*)([^\\s,;\\]\\}\\)]+)");
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|secret|api[_-]?key|mallToken)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)\\bAuthorization\\b\\s*[:=]\\s*Bearer\\s+[^\\s,;\\]\\}\\)]+");

    private final Tracer tracer;
    private final LangfuseProperties properties;
    private final Map<Span, Map<String, LinkedHashSet<String>>> appendedCsvAttributes =
            Collections.synchronizedMap(new WeakHashMap<>());

    public RagTracing() {
        this(GlobalOpenTelemetry.getTracer("com.example.ragagent"), new LangfuseProperties());
    }

    @Autowired
    public RagTracing(OpenTelemetry openTelemetry, LangfuseProperties properties) {
        this((openTelemetry == null ? GlobalOpenTelemetry.get() : openTelemetry).getTracer("com.example.ragagent"),
                properties);
    }

    public RagTracing(LangfuseProperties properties) {
        this(GlobalOpenTelemetry.getTracer("com.example.ragagent"), properties);
    }

    RagTracing(Tracer tracer) {
        this(tracer, new LangfuseProperties());
    }

    RagTracing(Tracer tracer, LangfuseProperties properties) {
        this.tracer = tracer;
        this.properties = properties == null ? new LangfuseProperties() : properties;
    }

    /**
     * 在指定 span 中执行代码块，并自动记录异常和结束 span。
     */
    public <T> T inSpan(String spanName, Callable<T> callable) {
        if (!properties.isEnabled()) {
            try {
                return callable.call();
            }
            catch (RuntimeException ex) {
                throw ex;
            }
            catch (Exception ex) {
                return rethrow(ex);
            }
        }
        Span span = startSpan(spanName);
        try (var ignored = makeCurrent(span)) {
            return callable.call();
        }
        catch (RuntimeException ex) {
            recordError(span, ex);
            throw ex;
        }
        catch (Exception ex) {
            recordError(span, ex);
            return rethrow(ex);
        }
        finally {
            endSpan(span);
        }
    }

    /**
     * 返回当前线程上下文中的 span。
     */
    public Span currentSpan() {
        return Span.current();
    }

    /**
     * 创建新的业务 span，并写入 Langfuse observation 名称。
     */
    public Span startSpan(String spanName) {
        if (!properties.isEnabled()) {
            return Span.getInvalid();
        }
        Span span = tracer.spanBuilder(spanName).startSpan();
        if (StringUtils.hasText(spanName)) {
            span.setAttribute("langfuse.observation.name", spanName);
        }
        return span;
    }

    /**
     * 将 span 放入当前上下文，未启用 tracing 时返回空 Scope。
     */
    public Scope makeCurrent(Span span) {
        if (!properties.isEnabled() || span == null) {
            return () -> {
            };
        }
        return span.makeCurrent();
    }

    /**
     * 结束 span，并清理为该 span 维护的追加属性缓存。
     */
    public void endSpan(Span span) {
        if (properties.isEnabled() && span != null) {
            appendedCsvAttributes.remove(span);
            span.end();
        }
    }

    /**
     * 为 span 写入非空字符串属性。
     */
    public void setAttribute(Span span, String key, String value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            span.setAttribute(key, value);
        }
    }

    /**
     * 为 span 写入 long 属性。
     */
    public void setAttribute(Span span, String key, long value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key)) {
            span.setAttribute(key, value);
        }
    }

    /**
     * 为 span 写入 double 属性。
     */
    public void setAttribute(Span span, String key, double value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key)) {
            span.setAttribute(key, value);
        }
    }

    /**
     * 为 span 写入 boolean 属性。
     */
    public void setAttribute(Span span, String key, boolean value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key)) {
            span.setAttribute(key, value);
        }
    }

    /**
     * 以去重 CSV 形式追加 span 属性，适合记录多次工具调用名称等集合值。
     */
    public void appendDistinctCsvAttribute(Span span, String key, String value) {
        if (!properties.isEnabled() || span == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        synchronized (appendedCsvAttributes) {
            Map<String, LinkedHashSet<String>> spanAttributes =
                    appendedCsvAttributes.computeIfAbsent(span, ignored -> new java.util.LinkedHashMap<>());
            LinkedHashSet<String> values =
                    spanAttributes.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            if (values.add(value)) {
                span.setAttribute(key, String.join(",", values));
            }
        }
    }

    /**
     * 在 span 上记录异常并标记错误状态。
     */
    public void recordError(Span span, Throwable ex) {
        if (!properties.isEnabled() || span == null || ex == null) {
            return;
        }
        span.recordException(ex);
        span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
        span.setAttribute("error.type", ex.getClass().getSimpleName());
    }

    /**
     * 按配置采集提示词文本，采集前会做敏感信息脱敏和长度截断。
     */
    public void capturePromptText(Span span, String key, String value) {
        if (!properties.isEnabled() || !properties.isCapturePrompt() || span == null
                || !StringUtils.hasText(key) || value == null) {
            return;
        }
        captureSanitizedText(span, key, value, promptCaptureLimit());
    }

    /**
     * 写入 Langfuse trace input 字段。
     */
    public void recordTraceInput(Span span, String value) {
        captureLangfuseTraceValue(span, "langfuse.trace.input", value);
    }

    /**
     * 写入 Langfuse trace output 字段。
     */
    public void recordTraceOutput(Span span, String value) {
        captureLangfuseTraceValue(span, "langfuse.trace.output", value);
    }

    private void captureLangfuseTraceValue(Span span, String key, String value) {
        if (!properties.isEnabled() || !properties.isCapturePrompt() || span == null
                || !StringUtils.hasText(key) || value == null) {
            return;
        }
        String sanitized = sanitizePromptText(value);
        if (StringUtils.hasText(sanitized)) {
            span.setAttribute(key, sanitized);
        }
    }

    /**
     * 按配置采集工具入参或返回结果，采集前会脱敏和截断。
     */
    public void captureToolPayload(Span span, String key, String value) {
        if (!properties.isEnabled() || !properties.isCaptureToolPayload() || span == null
                || !StringUtils.hasText(key) || value == null) {
            return;
        }
        captureSanitizedText(span, key, value, configuredCaptureLimit());
    }

    /**
     * 按配置采集 RAG 文档内容，采集前会脱敏和截断。
     */
    public void captureRagContent(Span span, String key, String value) {
        if (!properties.isEnabled() || !properties.isCaptureRagContent() || span == null
                || !StringUtils.hasText(key) || value == null) {
            return;
        }
        captureSanitizedText(span, key, value, configuredCaptureLimit());
    }

    private void captureSanitizedText(Span span, String key, String value, int limit) {
        String sanitized = sanitizePromptText(value);
        boolean truncated = sanitized.length() > limit;
        String captured = truncated ? sanitized.substring(0, limit) : sanitized;
        span.setAttribute(key, captured);
        span.setAttribute(key + ".length", (long) captured.length());
        span.setAttribute(key + ".truncated", truncated);
        writeLangfuseObservationValue(span, key, captured);
    }

    private void writeLangfuseObservationValue(Span span, String key, String captured) {
        if (!StringUtils.hasText(captured)) {
            return;
        }
        if (key.endsWith(".input")) {
            span.setAttribute("langfuse.observation.input", captured);
        }
        else if (key.endsWith(".output")) {
            span.setAttribute("langfuse.observation.output", captured);
        }
    }

    String sanitizePromptText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String sanitized = AUTHORIZATION_PATTERN.matcher(value).replaceAll("Authorization: [REDACTED]");
        sanitized = SECRET_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("$1$2[REDACTED]");
        sanitized = JSON_SECRET_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$3");
        return PHONE_PATTERN.matcher(sanitized).replaceAll("[REDACTED_PHONE]");
    }

    private int promptCaptureLimit() {
        return properties.getMaxCaptureChars() > 0 ? properties.getMaxCaptureChars() : PROMPT_CAPTURE_MAX_CHARS;
    }

    private int configuredCaptureLimit() {
        return properties.getMaxCaptureChars() > 0 ? properties.getMaxCaptureChars() : PROMPT_CAPTURE_MAX_CHARS;
    }

    /**
     * 提取前若干个非空文档 ID，便于在 span 上记录召回摘要。
     */
    public List<String> topDocumentIds(List<Document> documents, int limit) {
        if (documents == null || documents.isEmpty() || limit <= 0) {
            return List.of();
        }
        return documents.stream()
                .filter(document -> document != null && StringUtils.hasText(document.getId()))
                .limit(limit)
                .map(Document::getId)
                .toList();
    }

    /**
     * 生成不含正文的父文档摘要 JSON，适合默认观测字段。
     */
    public String safeParentsJson(List<Document> parents, int limit) {
        if (parents == null || parents.isEmpty() || limit <= 0) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        int rank = 1;
        for (Document parent : parents) {
            if (parent == null) {
                continue;
            }
            Map<String, Object> metadata = parent.getMetadata();
            String sourceId = metadataValue(metadata, "sourceId");
            String title = metadataValue(metadata, "title");
            items.add("{\"rank\":" + rank
                    + ",\"sourceId\":\"" + jsonEscape(sourceId)
                    + "\",\"title\":\"" + jsonEscape(title) + "\"}");
            rank++;
            if (items.size() >= limit) {
                break;
            }
        }
        return "[" + String.join(",", items) + "]";
    }

    /**
     * 生成包含父文档调试内容的 JSON，仅在允许采集 RAG 内容时返回正文。
     */
    public String debugParentsJson(List<Document> parents, int limit) {
        if (!properties.isCaptureRagContent() || parents == null || parents.isEmpty() || limit <= 0) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        int rank = 1;
        for (Document parent : parents) {
            if (parent == null) {
                continue;
            }
            Map<String, Object> metadata = parent.getMetadata();
            items.add("{\"rank\":" + rank
                    + ",\"id\":\"" + jsonEscape(parent.getId())
                    + "\",\"sourceId\":\"" + jsonEscape(metadataValue(metadata, "sourceId"))
                    + "\",\"title\":\"" + jsonEscape(metadataValue(metadata, "title"))
                    + "\",\"productId\":\"" + jsonEscape(metadataValue(metadata, "productId"))
                    + "\",\"skuId\":\"" + jsonEscape(metadataValue(metadata, "skuId"))
                    + "\",\"brand\":\"" + jsonEscape(metadataValue(metadata, "brand"))
                    + "\",\"category\":\"" + jsonEscape(metadataValue(metadata, "category"))
                    + "\",\"text\":\"" + jsonEscape(truncatedDocumentText(parent.getText())) + "\"}");
            rank++;
            if (items.size() >= limit) {
                break;
            }
        }
        return "[" + String.join(",", items) + "]";
    }

    /**
     * 生成包含子文档调试内容的 JSON，仅在允许采集 RAG 内容时返回正文。
     */
    public String debugChildrenJson(List<Document> children, int limit) {
        if (!properties.isCaptureRagContent() || children == null || children.isEmpty() || limit <= 0) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        int rank = 1;
        for (Document child : children) {
            if (child == null) {
                continue;
            }
            Map<String, Object> metadata = child.getMetadata();
            items.add("{\"rank\":" + rank
                    + ",\"id\":\"" + jsonEscape(child.getId())
                    + "\",\"parentId\":\"" + jsonEscape(metadataValue(metadata, "parentId"))
                    + "\",\"sourceId\":\"" + jsonEscape(metadataValue(metadata, "sourceId"))
                    + "\",\"title\":\"" + jsonEscape(metadataValue(metadata, "title"))
                    + "\",\"productId\":\"" + jsonEscape(metadataValue(metadata, "productId"))
                    + "\",\"skuId\":\"" + jsonEscape(metadataValue(metadata, "skuId"))
                    + "\",\"brand\":\"" + jsonEscape(metadataValue(metadata, "brand"))
                    + "\",\"category\":\"" + jsonEscape(metadataValue(metadata, "category"))
                    + "\",\"text\":\"" + jsonEscape(truncatedDocumentText(child.getText())) + "\"}");
            rank++;
            if (items.size() >= limit) {
                break;
            }
        }
        return "[" + String.join(",", items) + "]";
    }

    private String truncatedDocumentText(String text) {
        String sanitized = sanitizePromptText(text == null ? "" : text);
        return sanitized.length() > DEBUG_DOCUMENT_TEXT_MAX_CHARS
                ? sanitized.substring(0, DEBUG_DOCUMENT_TEXT_MAX_CHARS)
                : sanitized;
    }

    private String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || !StringUtils.hasText(key)) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }

    private String jsonEscape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character <= 0x1F) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    }
                    else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable ex) throws E {
        throw (E) ex;
    }
}
