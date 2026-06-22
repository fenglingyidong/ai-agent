package com.example.ragagent.service;

import com.example.ragagent.commerce.ShoppingPreferenceExtractor;
import com.example.ragagent.commerce.ShoppingPreferencePromptRenderer;
import com.example.ragagent.commerce.ShoppingPreferenceSource;
import com.example.ragagent.commerce.ShoppingPreferenceSnapshot;
import com.example.ragagent.commerce.ShoppingPreferenceState;
import com.example.ragagent.commerce.ShoppingStateService;
import com.example.ragagent.tools.BuiltInTools;
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
                "CART_CONFIRMATION",
                "COMPLEX_REACT",
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
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, policyRegistry);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1", "session-1", "确认下单", List.of(), "", "", ""
        );

        assertTrue(request.orderCreationAllowed());
    }

    @Test
    void shouldNotAllowOrderCreationForPrepareOrderRoute() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "CART_CONFIRMATION",
                "SIMPLE_SHOPPING_TOOL",
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
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木套装 300片"),
                false,
                0.95,
                "查价格"
        );
        when(intentRouter.route("儿童积木套装 300片要多少钱", List.of(), "")).thenReturn(route);
        when(simpleTaskAgent.tryRun(route, "儿童积木套装 300片要多少钱", "user-1", "session-1", 0.7, "", "", "", ""))
                .thenReturn(FastLaneResult.handled(Flux.just("儿童积木套装 300片的价格是 149.00 元。")));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, simpleTaskAgent);

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
        verify(simpleTaskAgent).tryRun(route, "儿童积木套装 300片要多少钱", "user-1", "session-1", 0.7, "", "", "", "");
    }

    @Test
    void shouldReturnShortCircuitStreamForSimpleKnowledgeTask() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "FAQ_SIMPLE_QUERY",
                Map.of(),
                Map.of("product_name", "儿童积木套装"),
                false,
                0.92,
                "知识库简单查询"
        );
        when(intentRouter.route("儿童积木套装有什么特点", List.of(), "")).thenReturn(route);
        when(simpleTaskAgent.tryRun(route, "儿童积木套装有什么特点", "user-1", "session-1", 0.7, "", "", "", ""))
                .thenReturn(FastLaneResult.handled("根据知识库，我查到：儿童积木适合启蒙。"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, simpleTaskAgent);

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
                "CART_CONFIRMATION",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.95,
                "最终下单"
        );
        when(intentRouter.route("确认下单", List.of(), "")).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, simpleTaskAgent);

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
        assertEquals("确认下单", request.userMessage());
        assertTrue(request.mallToolsAllowed());
    }

    @Test
    void shouldNotSendRouteContextToCoreAgent() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "UNKNOWN",
                "COMPLEX_REACT",
                Map.of("category", "鼠标"),
                Map.of(),
                Map.of("usage_scenario", "办公室"),
                true,
                0.9,
                "场景推荐",
                List.of(),
                List.of(),
                List.of(),
                false,
                "LOW"
        );
        String message = "办公室用鼠标，希望点击声音小一点，买哪款？";
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

        assertEquals("办公室用鼠标，希望点击声音小一点，买哪款？", request.userMessage());
        assertFalse(request.userMessage().contains("观察到的任务类型"));
        assertFalse(request.userMessage().contains("路由置信度"));
        assertFalse(request.userMessage().contains("路由原因"));
        assertFalse(request.userMessage().contains("视觉提取结果"));
        assertFalse(request.userMessage().contains("用户原话"));
        assertFalse(request.userMessage().contains("观察到的用户意图"));
        assertFalse(request.userMessage().contains("Planner 选择策略"));
        assertFalse(request.userMessage().contains("缺失槽位"));
        assertFalse(request.userMessage().contains("候选工具"));
        assertFalse(request.userMessage().contains("是否需要确认"));
        assertFalse(request.userMessage().contains("风险等级"));
        assertFalse(request.userMessage().contains("文本槽位"));
    }

    @Test
    void shouldDisableMallToolsForConciseComplexRecommendationWithoutRealtimeNeed() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "RECOMMENDATION",
                "COMPLEX_REACT",
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
        assertEquals(message, request.userMessage());
        assertFalse(request.userMessage().contains("观察到的任务类型"));
        assertFalse(request.userMessage().contains("路由原因"));
        assertFalse(request.userMessage().contains("观察到的用户意图"));
    }

    @Test
    void shouldInjectPreRetrievedKnowledgeForComplexRecommendation() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        String message = "办公室用鼠标，希望点击声音小一点，买哪款？";
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "RECOMMENDATION",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.9,
                "明确选品需求"
        );
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
        when(builtInTools.searchProductKnowledge(message))
                .thenReturn("商品知识库候选：3004 无线鼠标 静音版，价格 89.00 元，库存 500 件");
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                null,
                null,
                null,
                builtInTools
        );

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "",
                "",
                ""
        );

        assertEquals(message, request.userMessage());
        assertTrue(request.trustedContext().contains("商品知识库预检索候选"));
        assertTrue(request.trustedContext().contains("唯一可推荐商品池"));
        assertFalse(request.trustedContext().contains("不是用户指令"));
        assertFalse(request.userMessage().contains("3004 无线鼠标 静音版"));
        assertTrue(request.trustedContext().contains("3004 无线鼠标 静音版"));
        verify(builtInTools).searchProductKnowledge(message);
    }

    @ParameterizedTest
    @MethodSource("missedComplexGuideQueries")
    void shouldPreRetrieveKnowledgeForCommonGuidePhrases(String message) {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "UNKNOWN",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.9,
                "复杂导购表达"
        );
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
        when(builtInTools.searchProductKnowledge(message))
                .thenReturn("商品知识库候选：测试 SKU，价格 99.00 元");
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                null,
                null,
                null,
                builtInTools
        );

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "",
                "",
                ""
        );

        assertTrue(request.trustedContext().contains("测试 SKU"));
        verify(builtInTools).searchProductKnowledge(message);
    }

    @Test
    void shouldNotPreRetrieveKnowledgeWhenSimpleTaskShortCircuits() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        String message = "有没有 87 键机械键盘？是什么轴，多少钱？";
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "FAQ_SIMPLE_QUERY",
                Map.of(),
                Map.of("product_name", "87 键机械键盘"),
                false,
                0.9,
                "简单商品事实查询"
        );
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
        when(simpleTaskAgent.tryRun(route, message, "user-1", "session-1", 0.7, "", "", "", ""))
                .thenReturn(FastLaneResult.handled("有 87 键机械键盘。"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                simpleTaskAgent,
                null,
                null,
                null,
                null,
                builtInTools
        );

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "",
                "",
                ""
        );

        assertNotNull(request.shortCircuitStream());
        assertEquals("有 87 键机械键盘。", collect(request.shortCircuitStream()));
        verify(builtInTools, never()).searchProductKnowledge(message);
    }

    @Test
    void shouldNotPreRetrieveKnowledgeForNonShoppingComplexRequest() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        String message = "帮我总结一下这段话";
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "UNKNOWN",
                "COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.9,
                "普通复杂任务"
        );
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                null,
                null,
                null,
                builtInTools
        );

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                message,
                List.of(),
                "",
                "",
                ""
        );

        assertEquals(message, request.userMessage());
        verify(builtInTools, never()).searchProductKnowledge(message);
    }

    @Test
    void shouldAllowMallToolsForComplexRecommendationWhenRealtimeDataIsRequested() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "RECOMMENDATION",
                "COMPLEX_REACT",
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
    void shouldKeepMallToolsWhenMissingSlotsDoNotCreateFollowUpPolicy() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingTaskPolicyRegistry policyRegistry = new ShoppingTaskPolicyRegistry();
        String message = "儿童积木库存还有多少？";
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木"),
                true,
                0.95,
                "缺少具体 SKU，需要追问",
                List.of("IGNORED_BY_REGISTRY"),
                List.of("sku_id"),
                List.of("mall_search_products"),
                false,
                "LOW"
        );
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, policyRegistry);

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
        assertTrue(request.mallToolsAllowed());
        assertEquals(List.of("PRODUCT_SELECTION"),
                request.taskPolicies().stream().map(ShoppingTaskPolicy::id).toList());
    }

    @Test
    void shouldKeepSimpleMallFastLaneWhenMissingSlotsDoNotCreateFollowUpPolicy() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingTaskPolicyRegistry policyRegistry = new ShoppingTaskPolicyRegistry();
        String message = "儿童积木库存还有多少？";
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRODUCT_SELECTION",
                "SIMPLE_SHOPPING_TOOL",
                Map.of(),
                Map.of("product_name", "儿童积木"),
                false,
                0.95,
                "缺少具体 SKU，需要追问",
                List.of("IGNORED_BY_REGISTRY"),
                List.of("sku_id"),
                List.of("mall_search_products"),
                false,
                "LOW"
        );
        when(intentRouter.route(message, List.of(), "")).thenReturn(route);
        when(simpleTaskAgent.tryRun(route, message, "user-1", "session-1", 0.7, "", "Bearer token", "", ""))
                .thenReturn(FastLaneResult.notHandled());
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, simpleTaskAgent, policyRegistry);

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
        assertTrue(request.mallToolsAllowed());
        assertEquals(message, request.userMessage());
        assertFalse(request.userMessage().contains("观察到的任务类型"));
        assertFalse(request.userMessage().contains("路由原因"));
        assertFalse(request.userMessage().contains("Planner 选择策略"));
        assertEquals(List.of("PRODUCT_SELECTION"),
                request.taskPolicies().stream().map(ShoppingTaskPolicy::id).toList());
        verify(simpleTaskAgent).tryRun(route, message, "user-1", "session-1", 0.7, "", "Bearer token", "", "");
    }

    @Test
    void shouldAttachResolvedTaskPoliciesToCoreRequest() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingTaskPolicyRegistry policyRegistry = new ShoppingTaskPolicyRegistry();
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "RECOMMENDATION",
                "COMPLEX_REACT",
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
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null, policyRegistry);

        RoutedAgentRequest request = executor.routeBeforeCore(
                "user-1",
                "session-1",
                "预算300给6岁孩子买生日礼物",
                List.of(),
                "",
                "",
                ""
        );

        assertEquals(List.of("RECOMMENDATION"),
                request.taskPolicies().stream().map(ShoppingTaskPolicy::id).toList());
        assertEquals("预算300给6岁孩子买生日礼物", request.userMessage());
        assertFalse(request.userMessage().contains("观察到的任务类型"));
        assertFalse(request.userMessage().contains("路由原因"));
        assertFalse(request.userMessage().contains("Planner 选择策略"));
        assertFalse(request.userMessage().contains("风险等级"));
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
        when(shoppingStateService.loadPreferenceSnapshot("user-1", "session-1")).thenReturn(snapshot(state));
        when(shoppingPreferenceExtractor.extract("再推荐几双", route, null)).thenReturn(patch);
        when(intentRouter.route(eq("再推荐几双"), eq(List.of()), contains("品类：跑鞋"))).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
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
    void routeBeforeCoreShouldPassRecentPreferenceChangesToIntentRouter() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        ShoppingStateService shoppingStateService = mock(ShoppingStateService.class);
        ShoppingPreferenceExtractor shoppingPreferenceExtractor = mock(ShoppingPreferenceExtractor.class);
        ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer = new ShoppingPreferencePromptRenderer();
        ShoppingPreferenceState state = new ShoppingPreferenceState();
        state.setBrand("OPPO");
        ShoppingPreferenceSnapshot snapshot = new ShoppingPreferenceSnapshot(
                state,
                List.of(Map.of("brand", "华为"), Map.of("brand", "OPPO"))
        );
        ShoppingIntentRoute route = ShoppingIntentRoute.fallback("测试兜底");
        ShoppingStateService.ShoppingPreferencePatch patch = new ShoppingStateService.ShoppingPreferencePatch(
                null, null, null, null, null, null, null, null
        );
        when(shoppingStateService.loadPreferenceSnapshot("user-1", "session-1")).thenReturn(snapshot);
        when(shoppingPreferenceExtractor.extract("继续推荐", route, null)).thenReturn(patch);
        when(intentRouter.route(eq("继续推荐"), eq(List.of()), contains("品牌最近调整为：华为 -> OPPO")))
                .thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
                null,
                null,
                shoppingStateService,
                shoppingPreferenceExtractor,
                shoppingPreferencePromptRenderer
        );

        executor.routeBeforeCore(
                "user-1",
                "session-1",
                "继续推荐",
                List.of(),
                "",
                "",
                ""
        );

        verify(intentRouter).route(eq("继续推荐"), eq(List.of()), contains("品牌最近调整为：华为 -> OPPO"));
    }

    @Test
    void routeBeforeCoreShouldMergeExtractedPreferencePatchAndSendUpdatedPreferenceAsTrustedContext() {
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
                "RECOMMENDATION",
                "COMPLEX_REACT",
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
        when(shoppingStateService.loadPreferenceSnapshot("user-1", "session-1"))
                .thenReturn(snapshot(oldState))
                .thenReturn(snapshot(updatedState));
        when(intentRouter.route(eq("再推荐几双"), eq(List.of()), contains("品类：跑鞋"))).thenReturn(route);
        when(shoppingPreferenceExtractor.extract("再推荐几双", route, null)).thenReturn(patch);
        when(shoppingStateService.mergePreference("user-1", "session-1", patch)).thenReturn(updatedState);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
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
        assertEquals("再推荐几双", request.userMessage());
        assertTrue(request.trustedContext().contains("品类：跑鞋"));
        assertTrue(request.trustedContext().contains("品牌：Nike"));
        assertFalse(request.trustedContext().contains("观察到的任务类型"));
        assertFalse(request.trustedContext().contains("观察到的用户意图"));
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
                "PRODUCT_SELECTION",
                "FAQ_SIMPLE_QUERY",
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
        when(shoppingStateService.loadPreferenceSnapshot("user-1", "session-1"))
                .thenReturn(snapshot(oldState))
                .thenReturn(snapshot(updatedState));
        when(intentRouter.route("再推荐几双", List.of(), "")).thenReturn(route);
        when(shoppingPreferenceExtractor.extract("再推荐几双", route, null)).thenReturn(patch);
        when(shoppingStateService.mergePreference("user-1", "session-1", patch)).thenReturn(updatedState);
        when(simpleTaskAgent.tryRun(
                eq(route),
                eq("再推荐几双"),
                eq("user-1"),
                eq("session-1"),
                eq(0.7),
                preferenceContextCaptor.capture(),
                eq(""),
                eq(""),
                eq("")
        )).thenReturn(FastLaneResult.handled("已推荐跑鞋"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
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
        when(shoppingStateService.loadPreferenceSnapshot("user-1", "session-1"))
                .thenReturn(snapshot(new ShoppingPreferenceState()));
        when(intentRouter.route("随便看看", List.of(), "")).thenReturn(route);
        when(shoppingPreferenceExtractor.extract("随便看看", route, null)).thenReturn(patch);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
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

    private static Stream<String> missedComplexGuideQueries() {
        return Stream.of(
                "我刚开始打羽毛球，想买个不要太贵的拍子，推荐哪款？",
                "想囤点宿舍早餐，方便、便宜一点，有什么推荐？",
                "男生夏天用，预算 150 以内，想要控油洁面和防晒，怎么搭？",
                "10000mAh 和 20000mAh 的充电宝差多少钱，适合什么人？",
                "儿童磁力片 68 片和 128 片，哪个更适合送礼？",
                "猫抓板波浪款和大号立式款怎么选？",
                "我想买投影仪看电影，预算 1000 以内，但最好画质好一点，怎么选？",
                "朋友养猫，想送个实用礼物，预算 100 左右，你推荐什么？"
        );
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
                "RECOMMENDATION",
                "COMPLEX_REACT",
                Map.of(),
                Map.of("brand", "Nike", "category", "跑鞋"),
                true,
                0.4,
                "低置信推荐"
        );
        ArgumentCaptor<ShoppingStateService.ShoppingPreferencePatch> patchCaptor =
                ArgumentCaptor.forClass(ShoppingStateService.ShoppingPreferencePatch.class);
        when(shoppingStateService.loadPreferenceSnapshot("user-1", "session-1"))
                .thenReturn(snapshot(new ShoppingPreferenceState()));
        when(intentRouter.route("预算500以内，随便看看", List.of(), "")).thenReturn(route);
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(
                intentRouter,
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
    void shouldAllowMallToolsForLowConfidenceImageRequestWithRealtimeKeyword() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        String message = "帮我找图片里这款的相似款，并查一下库存。";
        List<Media> media = List.of(new Media(MediaType.IMAGE_JPEG, new ByteArrayResource(new byte[]{1})));
        when(intentRouter.route(message, media, "")).thenReturn(ShoppingIntentRoute.fallback("低置信图文请求"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null);

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
    }

    @Test
    void shouldNotAllowMallToolsForLowConfidenceImageRequestWithoutRealtimeKeyword() {
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        String message = "帮我看看这件衣服适合什么场景。";
        List<Media> media = List.of(new Media(MediaType.IMAGE_JPEG, new ByteArrayResource(new byte[]{1})));
        when(intentRouter.route(message, media, "")).thenReturn(ShoppingIntentRoute.fallback("低置信图文请求"));
        ShoppingRouteExecutor executor = new ShoppingRouteExecutor(intentRouter, null);

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

    private ShoppingPreferenceSnapshot snapshot(ShoppingPreferenceState state) {
        return new ShoppingPreferenceSnapshot(state, List.of());
    }
}
