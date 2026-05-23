package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpContextClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void shouldNotRegisterMallContextWhenTaskPolicyDisallowsMallTools() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        MallMcpContextClient contextClient = mock(MallMcpContextClient.class);
        ShoppingTaskPolicyRegistry policyRegistry = new ShoppingTaskPolicyRegistry();
        String message = "儿童积木库存还有多少？";
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRICE_STOCK_QUERY",
                "B_SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木"),
                true,
                0.95,
                "缺少具体 SKU，需要追问",
                List.of("FOLLOW_UP"),
                List.of("sku_id"),
                List.of("updateShoppingPreference"),
                false,
                "LOW"
        );
        when(intentRouter.route(message, List.of())).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, contextClient, null, policyRegistry);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "Bearer token",
                "",
                ""
        );

        assertEquals(null, request.shortCircuitStream());
        assertFalse(request.mallToolsAllowed());
        assertEquals(List.of("FOLLOW_UP"), request.taskPolicies().stream().map(ShoppingTaskPolicy::id).toList());
        verify(contextClient, never()).register("user-1", "session-1", "Bearer token", "", "");
    }

    @Test
    void shouldSkipSimpleMallFastLaneWhenTaskPolicyDisallowsMallTools() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        MallMcpContextClient contextClient = mock(MallMcpContextClient.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingTaskPolicyRegistry policyRegistry = new ShoppingTaskPolicyRegistry();
        String message = "儿童积木库存还有多少？";
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRICE_STOCK_QUERY",
                "B_SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木"),
                false,
                0.95,
                "缺少具体 SKU，需要追问",
                List.of("FOLLOW_UP"),
                List.of("sku_id"),
                List.of("updateShoppingPreference"),
                false,
                "LOW"
        );
        when(intentRouter.route(message, List.of())).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, contextClient, simpleTaskAgent, policyRegistry);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "Bearer token",
                "",
                ""
        );

        assertEquals(null, request.shortCircuitStream());
        assertFalse(request.mallToolsAllowed());
        assertTrue(request.userMessage().contains("Planner 选择策略：FOLLOW_UP"));
        verify(contextClient, never()).register("user-1", "session-1", "Bearer token", "", "");
        verify(simpleTaskAgent, never()).tryRun(route, message, "session-1", 0.7);
    }

    @Test
    void shouldAttachResolvedTaskPoliciesToCoreRequest() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingTaskPolicyRegistry policyRegistry = new ShoppingTaskPolicyRegistry();
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of("budget", "300"),
                true,
                0.95,
                "复杂推荐",
                List.of("PRODUCT_SELECTION", "RECOMMENDATION"),
                List.of(),
                List.of("searchProductKnowledge"),
                false,
                "MEDIUM"
        );
        when(intentRouter.route("预算300给6岁孩子买生日礼物", List.of())).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, null, policyRegistry);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                "预算300给6岁孩子买生日礼物",
                List.of(),
                "",
                "",
                ""
        );

        assertEquals(List.of("PRODUCT_SELECTION", "RECOMMENDATION"),
                request.taskPolicies().stream().map(ShoppingTaskPolicy::id).toList());
        assertTrue(request.userMessage().contains("Planner 选择策略：PRODUCT_SELECTION, RECOMMENDATION"));
        assertTrue(request.userMessage().contains("风险等级：MEDIUM"));
    }

    @Test
    void shouldRegisterMallContextForLowConfidenceImageRequestWithRealtimeKeyword() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        MallMcpContextClient contextClient = mock(MallMcpContextClient.class);
        String message = "帮我找图片里这款的相似款，并查一下库存。";
        List<Media> media = List.of(new Media(MediaType.IMAGE_JPEG, new ByteArrayResource(new byte[]{1})));
        when(intentRouter.route(message, media)).thenReturn(ShoppingIntentRoute.fallback("低置信图文请求"));
        when(contextClient.register("user-1", "session-1", "Bearer token", "", ""))
                .thenReturn(MallMcpContextClient.MallMcpContextRegistration.success());
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, contextClient, null);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                media,
                "Bearer token",
                "",
                ""
        );

        assertEquals(null, request.shortCircuitStream());
        assertTrue(request.mallToolsAllowed());
        verify(contextClient).register("user-1", "session-1", "Bearer token", "", "");
    }

    @Test
    void shouldNotRegisterMallContextForLowConfidenceImageRequestWithoutRealtimeKeyword() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        MallMcpContextClient contextClient = mock(MallMcpContextClient.class);
        String message = "帮我看看这件衣服适合什么场景。";
        List<Media> media = List.of(new Media(MediaType.IMAGE_JPEG, new ByteArrayResource(new byte[]{1})));
        when(intentRouter.route(message, media)).thenReturn(ShoppingIntentRoute.fallback("低置信图文请求"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, contextClient, null);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                media,
                "",
                "",
                ""
        );

        assertEquals(null, request.shortCircuitStream());
        assertFalse(request.mallToolsAllowed());
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }
}
