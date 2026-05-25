package com.example.ragagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

import java.util.List;
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

        assertEquals("QUERY_ATTRIBUTE", route.normalizedIntent());
        assertEquals("B_SIMPLE_SHOPPING_TOOL", route.normalizedTaskType());
        assertEquals(false, route.routeToCore());
        assertEquals(0.95, route.confidence());
        assertEquals("Nike", route.visualContext().get("brand_logo"));
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

        assertEquals("PRICE_STOCK_QUERY", route.normalizedIntent());
        assertEquals(false, route.routeToCore());
        assertEquals(List.of("PRODUCT_SELECTION"), route.taskPolicies());
        assertEquals(List.of("sku_id"), route.missingSlots());
        assertEquals(List.of("mall_get_product_detail"), route.toolCandidates());
        assertEquals(true, route.needConfirm());
        assertEquals("LOW", route.riskLevel());
        assertTrue(route.reason().contains("短路候选"));
    }

    @Test
    void routeShouldKeepComplexIntentOnCore() {
        RouterMocks mocks = routerMocks("""
                {
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

        assertEquals("COMPLEX_RECOMMENDATION", route.normalizedIntent());
        assertEquals("C_COMPLEX_REACT", route.normalizedTaskType());
        assertTrue(route.routeToCore());
    }

    @Test
    void routeShouldParseTaskPolicyMetadata() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "C_COMPLEX_REACT",
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

        assertEquals(List.of("PRODUCT_SELECTION", "FOLLOW_UP", "RECOMMENDATION"), route.taskPolicies());
        assertEquals(List.of("age"), route.missingSlots());
        assertEquals(List.of("searchProductKnowledge", "mall_search_products"), route.toolCandidates());
        assertEquals(false, route.needConfirm());
        assertEquals("MEDIUM", route.riskLevel());
    }

    @Test
    void routePromptShouldAskForTaskPolicies() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "B_SIMPLE_SHOPPING_TOOL",
                  "intent": "QUERY_ATTRIBUTE",
                  "visual_context": {},
                  "text_slots": {"target_attribute": "color"},
                  "route_to_core": false,
                  "confidence": 0.9,
                  "reason": "查属性"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        router.route("这件还有别的颜色吗", List.of());

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(mocks.requestSpec).system(systemCaptor.capture());
        String systemPrompt = systemCaptor.getValue();
        assertTrue(systemPrompt.contains("task_policies"));
        assertTrue(systemPrompt.contains("PRODUCT_SELECTION"));
        assertTrue(systemPrompt.contains("CART_CONFIRMATION"));
    }

    @Test
    void routeShouldIncludePreferenceContextWhenProvided() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "C_COMPLEX_REACT",
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
                  "task_type": "B_SIMPLE_SHOPPING_TOOL",
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
    void routeShouldParseSimpleRagTaskType() {
        RouterMocks mocks = routerMocks("""
                {
                  "task_type": "A_FAQ_SIMPLE_QUERY",
                  "intent": "PRODUCT_KNOWLEDGE_QUERY",
                  "visual_context": {},
                  "text_slots": {"product_name": "儿童积木套装"},
                  "route_to_core": false,
                  "confidence": 0.91,
                  "reason": "商品知识库事实查询"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("儿童积木套装有什么特点", List.of());

        assertEquals("A_FAQ_SIMPLE_QUERY", route.normalizedTaskType());
        assertEquals("PRODUCT_KNOWLEDGE_QUERY", route.normalizedIntent());
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
}
