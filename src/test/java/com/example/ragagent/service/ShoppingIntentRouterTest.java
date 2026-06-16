package com.example.ragagent.service;

import com.example.ragagent.observability.RagTracing;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingIntentRouterTest {

    @Test
    void routeShouldParseModelJsonResponse() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "SIMPLE_SHOPPING_TOOL",
                  "intent": "QUERY_ATTRIBUTE",
                  "visual_context": {"category": "运动鞋", "brand_logo": "Nike"},
                  "text_slots": {"target_attribute": "颜色"},
                  "route_to_core": false,
                  "confidence": 0.95,
                  "reason": "查颜色"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("还有别的颜色吗", List.of());

        assertEquals("UNKNOWN", route.normalizedIntent());
        assertEquals("SIMPLE_SHOPPING_TOOL", route.normalizedTaskType());
        assertEquals(false, route.routeToCore());
        assertEquals(0.95, route.confidence());
        assertEquals("Nike", route.visualContext().get("brand_logo"));
        assertEquals(Map.of(), route.textSlots());
    }

    @Test
    void routeShouldFallbackWhenModelReturnsInvalidJson() {
        RouterMocks mocks = routerMocks("我认为这是运动鞋，不是 JSON");
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("还有别的颜色吗", List.of());

        assertEquals("UNKNOWN", route.normalizedIntent());
        assertTrue(route.routeToCore());
        assertEquals(0.0, route.confidence());
    }

    @Test
    void routeShouldNormalizeHighConfidenceSimpleIntentToSkipCore() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "SIMPLE_SHOPPING_TOOL",
                  "intent": "PRICE_STOCK_QUERY",
                  "visual_context": {},
                  "text_slots": {"product_name": "儿童积木套装 300片", "target_attribute": "stock"},
                  "task_policies": ["PRODUCT_SELECTION"],
                  "missing_slots": ["sku_id"],
                  "tool_candidates": ["mall_get_product_detail"],
                  "need_confirm": true,
                  "risk_level": "LOW",
                  "route_to_core": true,
                  "confidence": 0.95,
                  "reason": "查库存"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("儿童积木套装 300片库存还有多少", List.of());

        assertEquals("UNKNOWN", route.normalizedIntent());
        assertEquals(false, route.routeToCore());
        assertEquals(List.of(), route.taskPolicies());
        assertEquals(List.of(), route.missingSlots());
        assertEquals(List.of(), route.toolCandidates());
        assertEquals(false, route.needConfirm());
        assertEquals("LOW", route.riskLevel());
        assertEquals("查库存", route.reason());
    }

    @Test
    void routeShouldKeepComplexIntentOnCore() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "COMPLEX_REACT",
                  "intent": "COMPLEX_RECOMMENDATION",
                  "visual_context": {},
                  "text_slots": {"budget": "300", "use_scene": "生日礼物"},
                  "route_to_core": true,
                  "confidence": 0.95,
                  "reason": "复杂推荐"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("预算300给5岁孩子买生日礼物，帮我比较推荐", List.of());

        assertEquals("UNKNOWN", route.normalizedIntent());
        assertEquals("COMPLEX_REACT", route.normalizedTaskType());
        assertTrue(route.routeToCore());
    }

    @Test
    void routeShouldIgnoreTaskPolicyMetadataFromModelJsonResponse() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "COMPLEX_REACT",
                  "intent": "COMPLEX_RECOMMENDATION",
                  "visual_context": {},
                  "text_slots": {"budget": "300", "use_scene": "生日礼物"},
                  "task_policies": ["PRODUCT_SELECTION", "FOLLOW_UP", "RECOMMENDATION"],
                  "missing_slots": ["age"],
                  "tool_candidates": ["searchProductKnowledge", "mall_search_products"],
                  "need_confirm": false,
                  "risk_level": "MEDIUM",
                  "route_to_core": true,
                  "confidence": 0.93,
                  "reason": "复杂推荐，需要补齐年龄"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("预算300买生日礼物，帮我推荐", List.of());

        assertEquals(List.of(), route.taskPolicies());
        assertEquals(List.of(), route.missingSlots());
        assertEquals(List.of(), route.toolCandidates());
        assertEquals(false, route.needConfirm());
        assertEquals("LOW", route.riskLevel());
    }

    @Test
    void routeShouldIgnoreLegacyModelFieldsOutsideLightweightSchema() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "COMPLEX_REACT",
                  "intent": "COMPLEX_RECOMMENDATION",
                  "visual_context": {"category": "鼠标"},
                  "text_slots": {"budget": "100"},
                  "preference_delta": {"usage_scenario": "办公室"},
                  "task_policies": ["FOLLOW_UP"],
                  "missing_slots": ["budget"],
                  "tool_candidates": [],
                  "need_confirm": true,
                  "risk_level": "HIGH",
                  "route_to_core": false,
                  "confidence": 0.9,
                  "reason": "场景推荐"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("办公室用鼠标，希望点击声音小一点，买哪款？", List.of());

        assertEquals("UNKNOWN", route.normalizedIntent());
        assertEquals("COMPLEX_REACT", route.normalizedTaskType());
        assertEquals(true, route.routeToCore());
        assertEquals(Map.of(), route.textSlots());
        assertEquals(List.of(), route.taskPolicies());
        assertEquals(List.of(), route.missingSlots());
        assertEquals(List.of(), route.toolCandidates());
        assertEquals(false, route.needConfirm());
        assertEquals("LOW", route.riskLevel());
        assertEquals("办公室", route.preferenceDelta().get("usage_scenario"));
        assertEquals("鼠标", route.visualContext().get("category"));
    }

    @Test
    void routePromptShouldAskOnlyForLightweightTaskType() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "SIMPLE_SHOPPING_TOOL",
                  "visual_context": {},
                  "preference_delta": {},
                  "confidence": 0.9,
                  "reason": "查属性"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        router.route("这件还有别的颜色吗", List.of());

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(mocks.requestSpec).system(systemCaptor.capture());
        String systemPrompt = systemCaptor.getValue();
        assertTrue(systemPrompt.contains("task_type: FAQ_SIMPLE_QUERY | SIMPLE_SHOPPING_TOOL | COMPLEX_REACT"));
        assertTrue(systemPrompt.contains("preference_delta"));
        assertTrue(!systemPrompt.contains("图像识别"));
        assertTrue(!systemPrompt.contains("task_policies"));
        assertTrue(!systemPrompt.contains("tool_candidates"));
        assertTrue(!systemPrompt.contains("missing_slots"));
        assertTrue(!systemPrompt.contains("route_to_core"));
        assertTrue(systemPrompt.contains("reason"));
        assertTrue(systemPrompt.contains("不超过 20 个字"));
        assertTrue(!systemPrompt.contains("\"target_attribute\": \"\""));
    }

    @Test
    void routePromptShouldIncludeImageInstructionsOnlyWhenMediaExists() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "COMPLEX_REACT",
                  "visual_context": {"category": "运动鞋"},
                  "preference_delta": {},
                  "confidence": 0.76,
                  "reason": "图文理解"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);
        Media media = new Media(MediaType.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2, 3}));

        router.route("看看图里是什么", List.of(media));

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(mocks.requestSpec).system(systemCaptor.capture());
        String systemPrompt = systemCaptor.getValue();
        assertTrue(systemPrompt.contains("图像识别"));
        assertTrue(systemPrompt.contains("visual_context 无图片时输出 {}"));
        assertTrue(systemPrompt.contains("category、brand_logo、main_color、product_name、style、material"));
    }

    @Test
    void routeShouldParsePreferenceDeltaFromModelJsonResponse() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "COMPLEX_REACT",
                  "visual_context": {},
                  "preference_delta": {
                    "category": "跑鞋",
                    "budget_max": 500,
                    "usage_scenario": "通勤",
                    "clear_fields": ["brand"]
                  },
                  "confidence": 0.88,
                  "reason": "推荐需主代理"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("预算500以内，通勤跑鞋，品牌不限", List.of());

        assertEquals("跑鞋", route.preferenceDelta().get("category"));
        assertEquals(500, route.preferenceDelta().get("budget_max"));
        assertEquals("通勤", route.preferenceDelta().get("usage_scenario"));
        assertEquals(List.of("brand"), route.preferenceDelta().get("clear_fields"));
    }

    @Test
    void routeShouldIncludePreferenceContextWhenProvided() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "COMPLEX_REACT",
                  "intent": "COMPLEX_RECOMMENDATION",
                  "visual_context": {},
                  "text_slots": {},
                  "route_to_core": true,
                  "confidence": 0.85,
                  "reason": "结合偏好继续推荐"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        router.route("再推荐几双", List.of(), "当前会话短期导购偏好：\n- 品类：跑鞋\n- 预算：500元以内");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mocks.requestSpec).user(userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();
        assertTrue(userPrompt.contains("当前会话短期导购偏好"));
        assertTrue(userPrompt.contains("品类：跑鞋"));
        assertTrue(userPrompt.contains("用户本轮输入"));
        assertTrue(userPrompt.contains("再推荐几双"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routeShouldAttachMediaToUserPromptWithOptionalPreferenceContext(boolean withPreferenceContext) {
        RouterMocks mocks = routerMocks("""
                {
                  "intent": "QUERY_ATTRIBUTE",
                  "visual_context": {"category": "运动鞋"},
                  "text_slots": {"target_attribute": "颜色"},
                  "route_to_core": false,
                  "confidence": 0.9,
                  "reason": "查颜色"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);
        Media media = new Media(MediaType.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2, 3}));
        String preferenceContext = withPreferenceContext
                ? "当前会话短期导购偏好：\n- 品类：跑鞋\n- 预算：500元以内"
                : "";

        router.route("再推荐几双", List.of(media), preferenceContext);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> userCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(mocks.requestSpec).user(userCaptor.capture());
        ChatClient.PromptUserSpec userSpec = mock(ChatClient.PromptUserSpec.class);
        when(userSpec.text(anyString())).thenReturn(userSpec);
        userCaptor.getValue().accept(userSpec);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(userSpec).text(textCaptor.capture());
        String userPrompt = textCaptor.getValue();
        assertTrue(userPrompt.contains("用户本轮输入"));
        assertTrue(userPrompt.contains("再推荐几双"));
        if (withPreferenceContext) {
            assertTrue(userPrompt.contains("当前会话短期导购偏好"));
            assertTrue(userPrompt.contains("品类：跑鞋"));
        }
        else {
            assertTrue(!userPrompt.contains("当前会话短期导购偏好"));
        }
        verify(userSpec).media(media);
    }

    @Test
    void routeShouldNotIncludePreferenceContextWhenMissing() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "SIMPLE_SHOPPING_TOOL",
                  "intent": "QUERY_ATTRIBUTE",
                  "visual_context": {},
                  "text_slots": {"target_attribute": "color"},
                  "route_to_core": false,
                  "confidence": 0.9,
                  "reason": "查属性"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        router.route("x", List.of());

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mocks.requestSpec).user(userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();
        assertTrue(!userPrompt.contains("当前会话短期导购偏好"));
    }

    @Test
    void routeShouldCaptureRouterInputAndOutput() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "COMPLEX_REACT",
                  "intent": "COMPLEX_RECOMMENDATION",
                  "visual_context": {},
                  "text_slots": {"category": "跑鞋"},
                  "route_to_core": true,
                  "confidence": 0.88,
                  "reason": "需要综合推荐"
                }
                """);
        RecordingTracing tracing = new RecordingTracing();
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient, tracing);

        router.route("推荐跑鞋", List.of(), "当前会话短期导购偏好：\n- 预算：500 元以内");

        assertTrue(tracing.text("llm.intent_router.input").contains("system:"));
        assertTrue(tracing.text("llm.intent_router.input").contains("user:"));
        assertTrue(tracing.text("llm.intent_router.input").contains("推荐跑鞋"));
        assertTrue(tracing.text("llm.intent_router.output").contains("\"task_type\":\"COMPLEX_REACT\""));
        assertTrue(tracing.text("llm.intent_router.output").contains("\"visual_context\":{}"));
        assertTrue(tracing.text("llm.intent_router.output").contains("\"preference_delta\":{}"));
        assertTrue(tracing.text("llm.intent_router.output").contains("\"confidence\":0.88"));
        assertTrue(tracing.text("llm.intent_router.output").contains("\"reason\":\"需要综合推荐\""));
        assertTrue(!tracing.text("llm.intent_router.output").contains("ShoppingIntentRoute["));
        assertTrue(!tracing.text("llm.intent_router.output").contains("textSlots"));
        assertTrue(!tracing.text("llm.intent_router.output").contains("routeToCore"));
        assertTrue(!tracing.text("llm.intent_router.output").contains("COMPLEX_RECOMMENDATION"));
    }

    @Test
    void routeShouldParseSimpleRagTaskType() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "FAQ_SIMPLE_QUERY",
                  "visual_context": {},
                  "confidence": 0.91,
                  "reason": "商品知识库事实查询"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("儿童积木套装有什么特点", List.of());

        assertEquals("FAQ_SIMPLE_QUERY", route.normalizedTaskType());
        assertEquals(false, route.routeToCore());
    }

    @Test
    void routeShouldParseSimpleShoppingTaskTypeWithoutLegacyIntentFields() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "SIMPLE_SHOPPING_TOOL",
                  "visual_context": {},
                  "confidence": 0.92,
                  "reason": "单步商城工具"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("购物车里有什么", List.of());

        assertEquals("SIMPLE_SHOPPING_TOOL", route.normalizedTaskType());
        assertEquals(false, route.routeToCore());
    }

    private RouterMocks routerMocks(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(ShoppingIntentRoute.class)).thenAnswer(invocation ->
                objectMapper.readValue(content, ShoppingIntentRoute.class));
        return new RouterMocks(chatClient, requestSpec);
    }

    private record RouterMocks(ChatClient chatClient, ChatClient.ChatClientRequestSpec requestSpec) {
    }

    private static final class RecordingTracing extends RagTracing {

        private final Map<String, String> captured = new LinkedHashMap<>();

        @Override
        public Span currentSpan() {
            return Span.getInvalid();
        }

        @Override
        public void capturePromptText(Span span, String key, String value) {
            captured.put(key, value);
        }

        private String text(String key) {
            return captured.getOrDefault(key, "");
        }
    }
}
