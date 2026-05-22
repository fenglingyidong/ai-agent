package com.example.ragagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
                  "route_to_core": true,
                  "confidence": 0.95,
                  "reason": "查库存"
                }
                """);
        ShoppingIntentRouter router = new ShoppingIntentRouter(mocks.chatClient);

        ShoppingIntentRoute route = router.route("儿童积木套装 300片库存还有多少", List.of());

        assertEquals("PRICE_STOCK_QUERY", route.normalizedIntent());
        assertEquals(false, route.routeToCore());
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

    @Test
    void routeShouldAttachMediaToUserPrompt() {
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

        router.route("这双鞋还有别的颜色吗", List.of(media));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> userCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(mocks.requestSpec).user(userCaptor.capture());
        ChatClient.PromptUserSpec userSpec = mock(ChatClient.PromptUserSpec.class);
        when(userSpec.text(anyString())).thenReturn(userSpec);
        userCaptor.getValue().accept(userSpec);

        verify(userSpec).media(media);
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
