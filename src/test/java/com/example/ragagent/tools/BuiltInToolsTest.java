package com.example.ragagent.tools;

import com.example.ragagent.commerce.ShoppingPreferenceState;
import com.example.ragagent.commerce.ShoppingPreferenceSource;
import com.example.ragagent.commerce.ShoppingStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuiltInToolsTest {

    @Test
    void searchProductKnowledgeShouldRenderRetrievedDocuments() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        BuiltInTools tools = new BuiltInTools(documentRetriever, shoppingStateService);
        Document document = Document.builder()
                .text("儿童积木套装 300片，适合 3 岁以上儿童。")
                .metadata(Map.of("title", "儿童积木", "skuId", "3020", "brand", "启蒙"))
                .build();
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(document));

        String result = tools.searchProductKnowledge("儿童积木");

        assertTrue(result.contains("回答约束"));
        assertTrue(result.contains("没有明确字段时不要当作知识库事实"));
        assertTrue(result.contains("[商品知识 1]"));
        assertTrue(result.contains("标题: 儿童积木"));
        assertTrue(result.contains("SKU: 3020"));
        assertTrue(result.contains("儿童积木套装 300片"));
    }

    @Test
    void searchProductKnowledgeShouldReuseSameRoundQueryCache() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        BuiltInTools tools = new BuiltInTools(documentRetriever, shoppingStateService);
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

    @Test
    void updateShoppingPreferenceShouldUseSpringAiToolContext() {
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        BuiltInTools tools = new BuiltInTools(documentRetriever, shoppingStateService);
        ToolContext toolContext = new ToolContext(Map.of("userId", "demo-user", "sessionId", "front-session"));
        ShoppingPreferenceState state = new ShoppingPreferenceState();
        state.setCategory("玩具");
        state.setBudgetMin(100);
        state.setBudgetMax(200);
        state.setBrand("启蒙");
        when(shoppingStateService.mergePreference(
                eq("demo-user"),
                eq("front-session"),
                any(ShoppingStateService.ShoppingPreferencePatch.class)
        )).thenReturn(state);

        String result = tools.updateShoppingPreference("玩具", 100, 200, "启蒙", "", "", "", "", toolContext);

        assertTrue(result.contains("品类：玩具"));
        assertTrue(result.contains("预算：100-200"));
        assertTrue(result.contains("品牌：启蒙"));
        ArgumentCaptor<ShoppingStateService.ShoppingPreferencePatch> patchCaptor =
                ArgumentCaptor.forClass(ShoppingStateService.ShoppingPreferencePatch.class);
        verify(shoppingStateService).mergePreference(
                eq("demo-user"),
                eq("front-session"),
                patchCaptor.capture()
        );
        assertEquals(ShoppingPreferenceSource.MODEL_TOOL.name(), patchCaptor.getValue().source());
        assertEquals(1.0, patchCaptor.getValue().confidence());
        assertEquals(null, patchCaptor.getValue().turnNo());
        assertTrue(patchCaptor.getValue().clearFields().isEmpty());
    }
}
