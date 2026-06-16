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
        verify(span).setAttribute("langfuse.observation.name", "rag.retrieve");
        verify(span).makeCurrent();
        verify(scope).close();
        verify(span).end();
    }

    @Test
    void recordTraceInputAndOutputShouldWriteLangfuseTraceFields() {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(true);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);

        enabledTracing.recordTraceInput(span, "用户问题");
        enabledTracing.recordTraceOutput(span, "最终回复");

        verify(span).setAttribute("langfuse.trace.input", "用户问题");
        verify(span).setAttribute("langfuse.trace.output", "最终回复");
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

    @Test
    void capturePromptTextShouldOnlyWriteSanitizedTextWhenCaptureEnabled() throws Exception {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(true);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);
        String expected = "手机号 [REDACTED_PHONE] password=[REDACTED] token: [REDACTED] Authorization: [REDACTED]";

        enabledTracing.capturePromptText(span, "llm.input",
                "手机号 13812345678 password=abc123 token: sk-test Authorization: Bearer abc");

        verify(span).setAttribute("llm.input", expected);
        verify(span).setAttribute("llm.input.length", (long) expected.length());
        verify(span).setAttribute("llm.input.truncated", false);
    }

    @Test
    void capturePromptTextShouldWriteLangfuseObservationInputAndOutput() {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(true);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);

        enabledTracing.capturePromptText(span, "llm.intent_router.input", "路由输入");
        enabledTracing.capturePromptText(span, "llm.intent_router.output", "路由输出");

        verify(span).setAttribute("langfuse.observation.input", "路由输入");
        verify(span).setAttribute("langfuse.observation.output", "路由输出");
    }

    @Test
    void capturePromptTextShouldSkipWhenCaptureDisabled() throws Exception {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(false);
        RagTracing disabledCaptureTracing = new RagTracing(mock(Tracer.class), properties);

        disabledCaptureTracing.capturePromptText(span, "llm.input", "password=abc123");

        verify(span, never()).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(span, never()).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(span, never()).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void capturePromptTextShouldTruncateLongText() {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCapturePrompt(true);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);

        enabledTracing.capturePromptText(span, "llm.input", "x".repeat(8_001));

        verify(span).setAttribute("llm.input", "x".repeat(8_000));
        verify(span).setAttribute("llm.input.length", 8_000L);
        verify(span).setAttribute("llm.input.truncated", true);
    }

    @Test
    void captureToolPayloadShouldWriteSanitizedPayloadOnlyWhenEnabled() {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCaptureToolPayload(true);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);
        String expected = "{\"skuId\":3020,\"token\":\"[REDACTED]\",\"phone\":\"[REDACTED_PHONE]\"}";

        enabledTracing.captureToolPayload(span, "tool.input",
                "{\"skuId\":3020,\"token\":\"secret-token\",\"phone\":\"13812345678\"}");

        verify(span).setAttribute("tool.input", expected);
        verify(span).setAttribute("tool.input.length", (long) expected.length());
        verify(span).setAttribute("tool.input.truncated", false);
    }

    @Test
    void captureToolPayloadShouldSkipWhenDisabled() {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        RagTracing disabledTracing = new RagTracing(mock(Tracer.class), properties);

        disabledTracing.captureToolPayload(span, "tool.input", "{\"skuId\":3020}");

        verify(span, never()).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void captureRagContentShouldUseRagSwitchAndMaxCaptureChars() {
        Span span = mock(Span.class);
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCaptureRagContent(true);
        properties.setMaxCaptureChars(12);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);

        enabledTracing.captureRagContent(span, "rag.parent.documents", "标题\npassword=secret-token\n正文");

        verify(span).setAttribute("rag.parent.documents", "标题\npassword=");
        verify(span).setAttribute("rag.parent.documents.length", 12L);
        verify(span).setAttribute("rag.parent.documents.truncated", true);
    }

    @Test
    void debugParentsJsonShouldIncludeSanitizedTextAndMetadataWhenRagCaptureEnabled() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCaptureRagContent(true);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);
        Document parent = Document.builder()
                .id("parent-1")
                .text("商品正文 token=secret-token 手机 13812345678")
                .metadata(Map.of(
                        "sourceId", "product-P1001",
                        "title", "旗舰降噪耳机",
                        "skuId", "1001",
                        "password", "secret-password"
                ))
                .build();

        String json = enabledTracing.debugParentsJson(List.of(parent), 10);

        assertTrue(json.contains("\"id\":\"parent-1\""));
        assertTrue(json.contains("\"sourceId\":\"product-P1001\""));
        assertTrue(json.contains("\"skuId\":\"1001\""));
        assertTrue(json.contains("\"text\":\"商品正文 token=[REDACTED] 手机 [REDACTED_PHONE]\""));
        assertFalse(json.contains("secret-token"));
        assertFalse(json.contains("secret-password"));
    }

    @Test
    void debugChildrenJsonShouldIncludeParentMappingAndSanitizedTextWhenRagCaptureEnabled() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setCaptureRagContent(true);
        RagTracing enabledTracing = new RagTracing(mock(Tracer.class), properties);
        Document child = Document.builder()
                .id("parent-1-child-0")
                .text("子分块正文 token=secret-token 手机 13812345678")
                .metadata(Map.of(
                        "docType", "rag-child",
                        "parentId", "parent-1",
                        "sourceId", "product-P1001",
                        "title", "旗舰降噪耳机",
                        "productId", "P1001",
                        "skuId", "1001",
                        "password", "secret-password"
                ))
                .build();

        String json = enabledTracing.debugChildrenJson(List.of(child), 10);

        assertTrue(json.contains("\"id\":\"parent-1-child-0\""));
        assertTrue(json.contains("\"parentId\":\"parent-1\""));
        assertTrue(json.contains("\"sourceId\":\"product-P1001\""));
        assertTrue(json.contains("\"skuId\":\"1001\""));
        assertTrue(json.contains("\"text\":\"子分块正文 token=[REDACTED] 手机 [REDACTED_PHONE]\""));
        assertFalse(json.contains("secret-token"));
        assertFalse(json.contains("secret-password"));
    }
}
