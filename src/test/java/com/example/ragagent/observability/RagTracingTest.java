package com.example.ragagent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagTracingTest {

    private final RagTracing tracing = new RagTracing();

    @Test
    void safeParentMetadataShouldKeepOnlyRankSourceIdAndTitle() {
        Document parent = Document.builder()
                .id("parent-1")
                .text("完整正文不能进入 Langfuse")
                .metadata(Map.of(
                        "sourceId", "product-P1001",
                        "title", "云跑 AirLite 缓震跑步鞋",
                        "token", "secret-token",
                        "password", "secret-password"
                ))
                .build();

        String json = tracing.safeParentsJson(List.of(parent), 10);

        assertTrue(json.contains("\"rank\":1"));
        assertTrue(json.contains("\"sourceId\":\"product-P1001\""));
        assertTrue(json.contains("\"title\":\"云跑 AirLite 缓震跑步鞋\""));
        assertFalse(json.contains("完整正文"));
        assertFalse(json.contains("secret-token"));
        assertFalse(json.contains("secret-password"));
    }

    @Test
    void topDocumentIdsShouldLimitToTen() {
        List<Document> documents = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(index -> Document.builder().id("child-" + index).text("child").build())
                .toList();

        List<String> ids = tracing.topDocumentIds(documents, 10);

        assertEquals(10, ids.size());
        assertEquals("child-1", ids.get(0));
        assertEquals("child-10", ids.get(9));
    }

    @Test
    void inSpanShouldRethrowCheckedExceptionWithoutWrapping() {
        IOException expected = new IOException("network failed");

        IOException actual = assertThrows(IOException.class, () -> tracing.inSpan("rag.retrieve", () -> {
            throw expected;
        }));

        assertSame(expected, actual);
    }

    @Test
    void inSpanShouldRethrowRuntimeExceptionWithoutWrapping() {
        IllegalArgumentException expected = new IllegalArgumentException("bad input");

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                () -> tracing.inSpan("rag.retrieve", () -> {
                    throw expected;
                }));

        assertSame(expected, actual);
    }

    @Test
    void disabledShouldRunCallableWithoutStartingSpan() {
        Tracer tracer = mock(Tracer.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(false);
        RagTracing disabledTracing = new RagTracing(tracer, properties);

        String result = disabledTracing.inSpan("rag.retrieve", () -> "ok");

        assertEquals("ok", result);
        verify(tracer, never()).spanBuilder("rag.retrieve");
    }

    @Test
    void enabledShouldStartMakeCurrentAndEndSpan() {
        Tracer tracer = mock(Tracer.class);
        SpanBuilder spanBuilder = mock(SpanBuilder.class);
        Span span = mock(Span.class);
        Scope scope = mock(Scope.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        when(tracer.spanBuilder("rag.retrieve")).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
        RagTracing enabledTracing = new RagTracing(tracer, properties);

        String result = enabledTracing.inSpan("rag.retrieve", () -> "ok");

        assertEquals("ok", result);
        verify(tracer).spanBuilder("rag.retrieve");
        verify(span).makeCurrent();
        verify(scope).close();
        verify(span).end();
    }

    @Test
    void disabledShouldNotWriteAttributes() {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(false);
        RagTracing disabledTracing = new RagTracing(mock(Tracer.class), properties);

        disabledTracing.setAttribute(span, "rag.status", "ok");

        verify(span, never()).setAttribute("rag.status", "ok");
    }

    @Test
    void disabledManualSpanApiShouldNotStartSpan() {
        Tracer tracer = mock(Tracer.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(false);
        RagTracing disabledTracing = new RagTracing(tracer, properties);

        Span span = disabledTracing.startSpan("rag.bm25.retrieve");
        disabledTracing.makeCurrent(span).close();
        disabledTracing.endSpan(span);

        assertSame(Span.getInvalid(), span);
        verify(tracer, never()).spanBuilder("rag.bm25.retrieve");
    }

    @Test
    void safeParentMetadataShouldEscapeJsonControlCharacters() {
        Document parent = Document.builder()
                .id("parent-1")
                .text("完整正文不能进入 Langfuse")
                .metadata(Map.of(
                        "sourceId", "product-\"P\\1001\"\n\t\b\f\u0001",
                        "title", "云跑\nAir\tLite \"缓震\" \\跑步鞋"
                ))
                .build();

        String json = tracing.safeParentsJson(List.of(parent), 10);

        assertEquals("[{\"rank\":1,\"sourceId\":\"product-\\\"P\\\\1001\\\"\\n\\t\\b\\f\\u0001\","
                + "\"title\":\"云跑\\nAir\\tLite \\\"缓震\\\" \\\\跑步鞋\"}]", json);
    }
}
