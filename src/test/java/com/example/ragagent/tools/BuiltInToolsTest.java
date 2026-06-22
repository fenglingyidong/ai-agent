package com.example.ragagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class BuiltInToolsTest {

    @Test
    void searchProductKnowledgeShouldRenderRetrievedDocuments() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        BuiltInTools tools = new BuiltInTools(documentRetriever);
        Document document = Document.builder()
                .text("儿童积木套装 300片，适合 3 岁以上儿童。")
                .metadata(Map.of("title", "儿童积木", "skuId", "3020", "brand", "启蒙"))
                .build();
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(document));

        String result = tools.searchProductKnowledge("儿童积木");

        assertTrue(result.contains("回答约束"));
        assertTrue(result.contains("价格桶只用于预算召回和初筛"));
        assertTrue(result.contains("不要引用或保留已废弃的历史快照口径"));
        assertTrue(result.contains("[商品知识 1]"));
        assertTrue(result.contains("标题: 儿童积木"));
        assertTrue(result.contains("SKU: 3020"));
        assertTrue(result.contains("儿童积木套装 300片"));
        assertTrue(!result.contains("导入快照"));
    }

    @Test
    void searchProductKnowledgeShouldAppendRealtimeMallDetailsForRetrievedSkus() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        ToolCallback mallDetailCallback = mallDetailCallback();
        BuiltInTools tools = new BuiltInTools(
                documentRetriever,
                List.of(providerWith(mallDetailCallback)),
                new ObjectMapper()
        );
        Document document = Document.builder()
                .text("机械键盘 红轴 87键，价格桶：100-200 元。")
                .metadata(Map.of("title", "机械键盘 红轴 87键", "skuId", "3002", "brand", "Mall Labs"))
                .build();
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(document));
        when(mallDetailCallback.call(anyString(), isNull()))
                .thenReturn("{\"ok\":true,\"data\":{\"skuId\":3002,\"skuName\":\"机械键盘 红轴 87键\",\"price\":129.00,\"stock\":260}}");

        String result = tools.searchProductKnowledge("87键机械键盘");

        assertTrue(result.contains("[商城实时详情 1]"));
        assertTrue(result.contains("SKU: 3002"));
        assertTrue(result.contains("\"price\":129.00"));
        assertTrue(result.contains("商品知识库只提供价格桶和非实时商品知识"));
        assertTrue(!result.contains("仍只是导入快照"));
        ArgumentCaptor<String> argumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mallDetailCallback).call(argumentCaptor.capture(), isNull());
        assertTrue(argumentCaptor.getValue().contains("\"skuId\":3002"));
    }

    @Test
    void searchProductKnowledgeShouldKeepRagResultWhenMallDetailFails() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        ToolCallback mallDetailCallback = mallDetailCallback();
        BuiltInTools tools = new BuiltInTools(
                documentRetriever,
                List.of(providerWith(mallDetailCallback)),
                new ObjectMapper()
        );
        Document document = Document.builder()
                .text("彩色中性笔套装 24支，价格桶：0-50 元。")
                .metadata(Map.of("title", "中性笔套装 彩色 24支", "skuId", "4072"))
                .build();
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(document));
        when(mallDetailCallback.call(anyString(), isNull()))
                .thenThrow(new IllegalStateException("mall-mcp 服务未启动或不可访问"));

        String result = tools.searchProductKnowledge("彩色中性笔套装");

        assertTrue(result.contains("彩色中性笔套装 24支"));
        assertTrue(result.contains("[商城实时详情 1]"));
        assertTrue(result.contains("查询状态: 失败"));
        assertTrue(result.contains("保留上方商品知识库结果"));
    }

    @Test
    void searchProductKnowledgeShouldNotCallMallWhenRetrievedDocumentHasNoSku() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        ToolCallback mallDetailCallback = mallDetailCallback();
        BuiltInTools tools = new BuiltInTools(
                documentRetriever,
                List.of(providerWith(mallDetailCallback)),
                new ObjectMapper()
        );
        Document document = Document.builder()
                .text("没有 SKU 的导购知识。")
                .metadata(Map.of("title", "导购知识"))
                .build();
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(document));

        String result = tools.searchProductKnowledge("导购知识");

        assertTrue(result.contains("没有 SKU 的导购知识。"));
        verify(mallDetailCallback, never()).call(anyString(), any());
    }

    @Test
    void searchProductKnowledgeShouldReuseSameRoundQueryCache() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        BuiltInTools tools = new BuiltInTools(documentRetriever);
        ToolContext toolContext = new ToolContext(Map.of(
                BuiltInTools.TOOL_CONTEXT_SEARCH_PRODUCT_KNOWLEDGE_CACHE,
                new ConcurrentHashMap<String, String>()
        ));
        Document document = Document.builder()
                .text("儿童积木套装 300片。")
                .metadata(Map.of("title", "儿童积木", "skuId", "3020"))
                .build();
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(document));

        String first = tools.searchProductKnowledge(" 儿童积木 ", toolContext);
        String second = tools.searchProductKnowledge("儿童积木", toolContext);

        assertEquals(first, second);
        verify(documentRetriever, times(1)).retrieve(any(Query.class));
    }

    private ToolCallbackProvider providerWith(ToolCallback... callbacks) {
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return provider;
    }

    private ToolCallback mallDetailCallback() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("mall_get_product_detail");
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

}
