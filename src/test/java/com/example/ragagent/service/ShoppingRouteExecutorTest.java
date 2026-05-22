package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpContextClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingRouteExecutorTest {

    @Test
    void shouldReturnShortCircuitStreamWhenSimpleTaskAgentHandlesConfidentMallRoute() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        MallMcpContextClient contextClient = mock(MallMcpContextClient.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRICE_STOCK_QUERY",
                Map.of(),
                Map.of("product_name", "儿童积木套装 300片"),
                false,
                0.95,
                "查价格"
        );
        when(intentRouter.route("儿童积木套装 300片要多少钱", List.of())).thenReturn(route);
        when(contextClient.register("user-1", "session-1", "", "", ""))
                .thenReturn(MallMcpContextClient.MallMcpContextRegistration.success());
        when(simpleTaskAgent.tryRun(route, "儿童积木套装 300片要多少钱", "session-1", 0.7))
                .thenReturn(FastLaneResult.handled(Flux.just("儿童积木套装 300片的价格是 149.00 元。")));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, contextClient, simpleTaskAgent);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                "儿童积木套装 300片要多少钱",
                List.of(),
                "",
                "",
                ""
        );

        assertNotNull(request.shortCircuitStream());
        assertEquals("儿童积木套装 300片的价格是 149.00 元。", collect(request.shortCircuitStream()));
        verify(contextClient).register("user-1", "session-1", "", "", "");
        verify(simpleTaskAgent).tryRun(route, "儿童积木套装 300片要多少钱", "session-1", 0.7);
    }

    @Test
    void shouldReturnShortCircuitStreamForSimpleKnowledgeTask() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_KNOWLEDGE_QUERY",
                "A_FAQ_SIMPLE_QUERY",
                Map.of(),
                Map.of("product_name", "儿童积木套装"),
                false,
                0.92,
                "知识库简单查询"
        );
        when(intentRouter.route("儿童积木套装有什么特点", List.of())).thenReturn(route);
        when(simpleTaskAgent.tryRun(route, "儿童积木套装有什么特点", "session-1", 0.7))
                .thenReturn(FastLaneResult.handled("根据知识库，我查到：儿童积木适合启蒙。"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, simpleTaskAgent);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                "儿童积木套装有什么特点",
                List.of(),
                "",
                "",
                ""
        );

        assertNotNull(request.shortCircuitStream());
        assertEquals("根据知识库，我查到：儿童积木适合启蒙。", collect(request.shortCircuitStream()));
    }

    @Test
    void shouldRouteCreateOrderToCore() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CREATE_ORDER",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.95,
                "最终下单"
        );
        when(intentRouter.route("确认下单", List.of())).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, simpleTaskAgent);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                "确认下单",
                List.of(),
                "",
                "",
                ""
        );

        assertEquals(null, request.shortCircuitStream());
        assertEquals(true, request.userMessage().contains("观察到的任务类型：C_COMPLEX_REACT"));
        assertTrue(request.mallToolsAllowed());
    }

    @Test
    void shouldDisableMallToolsForConciseComplexRecommendationWithoutRealtimeNeed() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of("budget", "300", "age", "5岁", "candidates", "儿童积木,降噪耳机"),
                true,
                0.95,
                "复杂推荐对比"
        );
        String message = "预算300以内，5岁小孩生日礼物，儿童积木和降噪耳机哪个更合适？只用三句话回答。";
        when(intentRouter.route(message, List.of())).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, null);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "",
                "",
                ""
        );

        assertEquals(null, request.shortCircuitStream());
        assertFalse(request.mallToolsAllowed());
        assertTrue(request.userMessage().contains("观察到的用户意图：COMPLEX_RECOMMENDATION"));
    }

    @Test
    void shouldAllowMallToolsForComplexRecommendationWhenRealtimeDataIsRequested() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of("budget", "300", "product_name", "儿童积木"),
                true,
                0.95,
                "需要实时价格再推荐"
        );
        String message = "预算300以内，查一下儿童积木实时价格和库存再推荐。";
        when(intentRouter.route(message, List.of())).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, null);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "",
                "",
                ""
        );

        assertEquals(null, request.shortCircuitStream());
        assertTrue(request.mallToolsAllowed());
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }
}
