package com.example.ragagent.observability;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
public class RagTracing {

    private final Tracer tracer;
    private final LangfuseProperties properties;

    public RagTracing() {
        this(GlobalOpenTelemetry.getTracer("com.example.ragagent"), new LangfuseProperties());
    }

    @Autowired
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

    public Span currentSpan() {
        return Span.current();
    }

    public Span startSpan(String spanName) {
        if (!properties.isEnabled()) {
            return Span.getInvalid();
        }
        return tracer.spanBuilder(spanName).startSpan();
    }

    public Scope makeCurrent(Span span) {
        if (!properties.isEnabled() || span == null) {
            return () -> {
            };
        }
        return span.makeCurrent();
    }

    public void endSpan(Span span) {
        if (properties.isEnabled() && span != null) {
            span.end();
        }
    }

    public void setAttribute(Span span, String key, String value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            span.setAttribute(key, value);
        }
    }

    public void setAttribute(Span span, String key, long value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key)) {
            span.setAttribute(key, value);
        }
    }

    public void setAttribute(Span span, String key, double value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key)) {
            span.setAttribute(key, value);
        }
    }

    public void setAttribute(Span span, String key, boolean value) {
        if (properties.isEnabled() && span != null && StringUtils.hasText(key)) {
            span.setAttribute(key, value);
        }
    }

    public void recordError(Span span, Throwable ex) {
        if (!properties.isEnabled() || span == null || ex == null) {
            return;
        }
        span.recordException(ex);
        span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
        span.setAttribute("error.type", ex.getClass().getSimpleName());
    }

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
