package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpContextClient;
import com.example.ragagent.commerce.ShoppingPreferenceExtractor;
import com.example.ragagent.commerce.ShoppingPreferencePromptRenderer;
import com.example.ragagent.commerce.ShoppingPreferenceSource;
import com.example.ragagent.commerce.ShoppingPreferenceState;
import com.example.ragagent.commerce.ShoppingStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingRouteExecutorTest {

    @Test
    void shouldAllowOrderCreationOnlyForConfirmedCreateOrderRoute() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingTaskPolicyRegistry policyRegistry = new ShoppingTaskPolicyRegistry();
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CREATE_ORDER",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of("confirmationId", "confirm-1"),
                true,
                0.95,
                "用户明确二次确认",
                List.of("CART_CONFIRMATION"),
                List.of(),
                List.of("mall_create_order"),
                true,
                "HIGH"
        );
        when(intentRouter.route("确认下单", List.of(), "")).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, null, policyRegistry);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1", "session-1", "确认下单", List.of(), "", "", ""
        );

        assertTrue(request.orderCreationAllowed());
    }

    @Test
    void shouldNotAllowOrderCreationForPrepareOrderRoute() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PREPARE_ORDER",
                "B_SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of(),
                true,
                0.95,
                "确认订单摘要"
        );
        when(intentRouter.route("帮我确认订单", List.of(), "")).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, null);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1", "session-1", "帮我确认订单", List.of(), "", "", ""
        );

        assertFalse(request.orderCreationAllowed());
    }

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
        when(intentRouter.route("儿童积木套装 300片要多少钱", List.of(), "")).thenReturn(route);
        when(contextClient.register("user-1", "session-1", "", "", ""))
                .thenReturn(MallMcpContextClient.MallMcpContextRegistration.success());
        when(simpleTaskAgent.tryRun(route, "儿童积木套装 300片要多少钱", "session-1", 0.7, ""))
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
        verify(simpleTaskAgent).tryRun(route, "儿童积木套装 300片要多少钱", "session-1", 0.7, "");
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
        when(intentRouter.route("儿童积木套装有什么特点", List.of(), "")).thenReturn(route);
        when(simpleTaskAgent.tryRun(route, "儿童积木套装有什么特点", "session-1", 0.7, ""))
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
        when(intentRouter.route("确认下单", List.of(), "")).thenReturn(route);
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
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
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
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
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
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
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
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
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
        verify(simpleTaskAgent, never()).tryRun(route, message, "session-1", 0.7, "");
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
        when(intentRouter.route("预算300给6岁孩子买生日礼物", List.of(), "")).thenReturn(route);
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
    void routeBeforeCoreShouldPassPreferenceContextToIntentRouter() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        ShoppingPreferenceExtractor shoppingPreferenceExtractor = mock(ShoppingPreferenceExtractor.class);
        ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer = new ShoppingPreferencePromptRenderer();
        ShoppingPreferenceState state = new ShoppingPreferenceState();
        state.setCategory("跑鞋");
        state.setBudgetMax(500);
        ShoppingIntentRoute route = ShoppingIntentRoute.fallback("测试兜底");
        ShoppingStateService.ShoppingPreferencePatch patch = new ShoppingStateService.ShoppingPreferencePatch(
                null, null, null, null, null, null, null, null
        );
        when(shoppingStateService.loadPreference("user-1", "session-1")).thenReturn(state);
        when(shoppingPreferenceExtractor.extract("再推荐几双", route, null)).thenReturn(patch);
        when(intentRouter.route(eq("再推荐几双"), eq(List.of()), contains("品类：跑鞋"))).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                null,
                shoppingStateService,
                shoppingPreferenceExtractor,
                shoppingPreferencePromptRenderer
        );

        executor.routeBeforeCore(
                "user-1",
                "session-1",
                "再推荐几双",
                List.of(),
                "",
                "",
                ""
        );

        verify(intentRouter).route(eq("再推荐几双"), eq(List.of()), contains("预算：500元以内"));
    }

    @Test
    void routeBeforeCoreShouldMergeExtractedPreferencePatchAndUseUpdatedContextForCore() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        ShoppingPreferenceExtractor shoppingPreferenceExtractor = mock(ShoppingPreferenceExtractor.class);
        ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer = new ShoppingPreferencePromptRenderer();
        ShoppingPreferenceState oldState = new ShoppingPreferenceState();
        oldState.setCategory("跑鞋");
        ShoppingPreferenceState updatedState = new ShoppingPreferenceState();
        updatedState.setCategory("跑鞋");
        updatedState.setBrand("Nike");
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.95,
                "继续推荐"
        );
        ShoppingStateService.ShoppingPreferencePatch patch = new ShoppingStateService.ShoppingPreferencePatch(
                null,
                null,
                null,
                "Nike",
                null,
                null,
                null,
                null,
                null,
                ShoppingPreferenceSource.ROUTER_SLOT.name(),
                0.9,
                null
        );
        when(shoppingStateService.loadPreference("user-1", "session-1"))
                .thenReturn(oldState)
                .thenReturn(updatedState);
        when(intentRouter.route(eq("再推荐几双"), eq(List.of()), contains("品类：跑鞋"))).thenReturn(route);
        when(shoppingPreferenceExtractor.extract("再推荐几双", route, null)).thenReturn(patch);
        when(shoppingStateService.mergePreference("user-1", "session-1", patch)).thenReturn(updatedState);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                null,
                shoppingStateService,
                shoppingPreferenceExtractor,
                shoppingPreferencePromptRenderer
        );

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                "再推荐几双",
                List.of(),
                "",
                "",
                ""
        );

        verify(shoppingStateService).mergePreference("user-1", "session-1", patch);
        assertTrue(request.userMessage().contains("品牌：Nike"));
        assertTrue(request.userMessage().contains("观察到的用户意图"));
    }

    @Test
    void routeBeforeCoreShouldPassUpdatedPreferenceContextToSimpleTaskAgent() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        ShoppingPreferenceExtractor shoppingPreferenceExtractor = mock(ShoppingPreferenceExtractor.class);
        ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer = new ShoppingPreferencePromptRenderer();
        ShoppingPreferenceState oldState = new ShoppingPreferenceState();
        ShoppingPreferenceState updatedState = new ShoppingPreferenceState();
        updatedState.setCategory("跑鞋");
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_KNOWLEDGE_QUERY",
                "A_FAQ_SIMPLE_QUERY",
                Map.of(),
                Map.of("category", "跑鞋"),
                false,
                0.95,
                "简单知识库查询"
        );
        ShoppingStateService.ShoppingPreferencePatch patch = new ShoppingStateService.ShoppingPreferencePatch(
                "跑鞋",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ShoppingPreferenceSource.ROUTER_SLOT.name(),
                0.9,
                null
        );
        ArgumentCaptor<String> preferenceContextCaptor = ArgumentCaptor.forClass(String.class);
        when(shoppingStateService.loadPreference("user-1", "session-1"))
                .thenReturn(oldState)
                .thenReturn(updatedState);
        when(intentRouter.route("再推荐几双", List.of(), "")).thenReturn(route);
        when(shoppingPreferenceExtractor.extract("再推荐几双", route, null)).thenReturn(patch);
        when(shoppingStateService.mergePreference("user-1", "session-1", patch)).thenReturn(updatedState);
        when(simpleTaskAgent.tryRun(
                eq(route),
                eq("再推荐几双"),
                eq("session-1"),
                eq(0.7),
                preferenceContextCaptor.capture()
        )).thenReturn(FastLaneResult.handled("已推荐跑鞋"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                simpleTaskAgent,
                null,
                shoppingStateService,
                shoppingPreferenceExtractor,
                shoppingPreferencePromptRenderer
        );

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                "再推荐几双",
                List.of(),
                "",
                "",
                ""
        );

        assertEquals("已推荐跑鞋", collect(request.shortCircuitStream()));
        assertTrue(preferenceContextCaptor.getValue().contains("品类：跑鞋"));
    }

    @ParameterizedTest
    @MethodSource("ignoredPreferencePatches")
    void routeBeforeCoreShouldIgnoreMissingOrZeroConfidencePreferencePatch(
            ShoppingStateService.ShoppingPreferencePatch patch) {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        ShoppingPreferenceExtractor shoppingPreferenceExtractor = mock(ShoppingPreferenceExtractor.class);
        ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer = new ShoppingPreferencePromptRenderer();
        ShoppingIntentRoute route = ShoppingIntentRoute.fallback("测试兜底");
        when(shoppingStateService.loadPreference("user-1", "session-1"))
                .thenReturn(new ShoppingPreferenceState());
        when(intentRouter.route("随便看看", List.of(), "")).thenReturn(route);
        when(shoppingPreferenceExtractor.extract("随便看看", route, null)).thenReturn(patch);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                null,
                shoppingStateService,
                shoppingPreferenceExtractor,
                shoppingPreferencePromptRenderer
        );

        executor.routeBeforeCore(
                "user-1",
                "session-1",
                "随便看看",
                List.of(),
                "",
                "",
                ""
        );

        verify(shoppingStateService, never()).mergePreference(eq("user-1"), eq("session-1"), any());
    }

    private static Stream<ShoppingStateService.ShoppingPreferencePatch> ignoredPreferencePatches() {
        return Stream.of(
                null,
                new ShoppingStateService.ShoppingPreferencePatch(
                        "跑鞋",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        ShoppingPreferenceSource.ROUTER_SLOT.name(),
                        0.0,
                        null
                )
        );
    }

    @Test
    void routeBeforeCoreShouldSkipLowConfidenceRouteSlotPreferenceAndKeepExplicitTextPreference() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        ShoppingPreferenceExtractor shoppingPreferenceExtractor = new ShoppingPreferenceExtractor();
        ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer = new ShoppingPreferencePromptRenderer();
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of("brand", "Nike", "category", "跑鞋"),
                true,
                0.4,
                "低置信推荐"
        );
        ArgumentCaptor<ShoppingStateService.ShoppingPreferencePatch> patchCaptor =
                ArgumentCaptor.forClass(ShoppingStateService.ShoppingPreferencePatch.class);
        when(shoppingStateService.loadPreference("user-1", "session-1"))
                .thenReturn(new ShoppingPreferenceState());
        when(intentRouter.route("预算500以内，随便看看", List.of(), "")).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                null,
                shoppingStateService,
                shoppingPreferenceExtractor,
                shoppingPreferencePromptRenderer
        );

        executor.routeBeforeCore(
                "user-1",
                "session-1",
                "预算500以内，随便看看",
                List.of(),
                "",
                "",
                ""
        );

        verify(shoppingStateService).mergePreference(eq("user-1"), eq("session-1"), patchCaptor.capture());
        ShoppingStateService.ShoppingPreferencePatch mergedPatch = patchCaptor.getValue();
        assertEquals(ShoppingPreferenceSource.USER_EXPLICIT.name(), mergedPatch.source());
        assertEquals(500, mergedPatch.budgetMax());
        assertEquals(null, mergedPatch.brand());
    }

    @Test
    void shouldRegisterMallContextForLowConfidenceImageRequestWithRealtimeKeyword() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        MallMcpContextClient contextClient = mock(MallMcpContextClient.class);
        String message = "帮我找图片里这款的相似款，并查一下库存。";
        List<Media> media = List.of(new Media(MediaType.IMAGE_JPEG, new ByteArrayResource(new byte[]{1})));
        when(intentRouter.route(message, media, "")).thenReturn(ShoppingIntentRoute.fallback("低置信图文请求"));
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
        when(intentRouter.route(message, media, "")).thenReturn(ShoppingIntentRoute.fallback("低置信图文请求"));
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
