package com.example.ragagent.service;

import com.example.ragagent.commerce.ShoppingPreferenceExtractor;
import com.example.ragagent.commerce.ShoppingPreferencePromptRenderer;
import com.example.ragagent.commerce.ShoppingPreferenceSource;
import com.example.ragagent.commerce.ShoppingPreferenceSnapshot;
import com.example.ragagent.commerce.ShoppingPreferenceState;
import com.example.ragagent.commerce.ShoppingStateService;
import com.example.ragagent.mall.MallMcpContextClient;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.tools.BuiltInTools;
import io.opentelemetry.api.trace.Span;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ShoppingRouteExecutor {

    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.7;
    private static final String EMPTY_KNOWLEDGE_MESSAGE = "商品知识库中没有检索到相关内容。";
    private static final Pattern MALL_KEYWORD_TEXT = Pattern.compile(".*(商品|价格|多少钱|库存|SKU|购物车|加购|加入购物车|下单|订单|确认订单|买|相似款|推荐).*");
    private static final Pattern HARD_MALL_OPERATION_TEXT = Pattern.compile(".*(购物车|加购|加入购物车|下单|订单|确认订单|创建订单|购买这|买这|买它).*");
    private static final Pattern ORDER_CREATION_TEXT = Pattern.compile(".*(确认下单|提交订单|创建订单|立即下单|直接下单|下单吧|买这|买它|购买这).*");
    private static final Pattern REALTIME_MALL_TOOL_TEXT = Pattern.compile(".*(实时|价格|多少钱|库存|现货|SKU|sku|商品详情|详情|评价|优惠|促销|这款|这个商品|相似款).*");
    private static final Pattern NO_REALTIME_MALL_TOOL_TEXT = Pattern.compile(".*(不需要|不用|无需|不要).*?(实时|价格|库存|商城|搜索|查价).*");
    private static final Pattern PRE_RETRIEVAL_ACTION_TEXT = Pattern.compile(".*(想买|买个|买哪款|买哪个|买什么|推荐|选哪个|怎么选|怎么搭|对比|比较|区别|差多少钱|适合|合适|送礼|礼物|配一套|搭配|组合|添几样|预算|不要太贵|便宜|实用).*");
    private static final Pattern PRE_RETRIEVAL_PRODUCT_TEXT = Pattern.compile(".*(商品|SKU|sku|办公室|桌面|厨房|收纳|做饭|鼠标|键盘|机械键盘|台灯|护眼|静音|低噪音|人体工学|跑步|鞋|耳机|猫|猫粮|猫砂|猫抓板|露营|户外|徒步|装备|水杯|纸巾|书架|笔|充电宝|磁力片|羽毛球|拍子|早餐|宿舍|燕麦|洁面|防晒|控油|投影仪|投影|空气炸锅|电热水壶|置物架).*");

    @Autowired(required = false)
    private ShoppingIntentRouter intentRouter;

    @Value("${app.ai.intent-router.confidence-threshold:0.7}")
    private double confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;

    @Autowired(required = false)
    private MallMcpContextClient mallMcpContextClient;

    @Autowired(required = false)
    private SimpleTaskAgent simpleTaskAgent;

    @Autowired(required = false)
    private ShoppingTaskPolicyRegistry taskPolicyRegistry;

    @Autowired(required = false)
    private ShoppingStateService shoppingStateService;

    @Autowired(required = false)
    private ShoppingPreferenceExtractor shoppingPreferenceExtractor;

    @Autowired(required = false)
    private ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer;

    @Autowired(required = false)
    private BuiltInTools builtInTools;

    @Autowired(required = false)
    private RagTracing tracing;

    public ShoppingRouteExecutor() {
    }

    public ShoppingRouteExecutor(ShoppingIntentRouter intentRouter, MallMcpContextClient mallMcpContextClient) {
        this.intentRouter = intentRouter;
        this.mallMcpContextClient = mallMcpContextClient;
    }

    public ShoppingRouteExecutor(ShoppingIntentRouter intentRouter,
                                 MallMcpContextClient mallMcpContextClient,
                                 SimpleTaskAgent simpleTaskAgent) {
        this.intentRouter = intentRouter;
        this.mallMcpContextClient = mallMcpContextClient;
        this.simpleTaskAgent = simpleTaskAgent;
    }

    public ShoppingRouteExecutor(ShoppingIntentRouter intentRouter,
                                 MallMcpContextClient mallMcpContextClient,
                                 SimpleTaskAgent simpleTaskAgent,
                                 ShoppingTaskPolicyRegistry taskPolicyRegistry) {
        this.intentRouter = intentRouter;
        this.mallMcpContextClient = mallMcpContextClient;
        this.simpleTaskAgent = simpleTaskAgent;
        this.taskPolicyRegistry = taskPolicyRegistry;
    }

    public ShoppingRouteExecutor(ShoppingIntentRouter intentRouter,
                                 MallMcpContextClient mallMcpContextClient,
                                 SimpleTaskAgent simpleTaskAgent,
                                 ShoppingTaskPolicyRegistry taskPolicyRegistry,
                                 ShoppingStateService shoppingStateService,
                                 ShoppingPreferenceExtractor shoppingPreferenceExtractor,
                                 ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer) {
        this(intentRouter,
                mallMcpContextClient,
                simpleTaskAgent,
                taskPolicyRegistry,
                shoppingStateService,
                shoppingPreferenceExtractor,
                shoppingPreferencePromptRenderer,
                null);
    }

    public ShoppingRouteExecutor(ShoppingIntentRouter intentRouter,
                                 MallMcpContextClient mallMcpContextClient,
                                 SimpleTaskAgent simpleTaskAgent,
                                 ShoppingTaskPolicyRegistry taskPolicyRegistry,
                                 ShoppingStateService shoppingStateService,
                                 ShoppingPreferenceExtractor shoppingPreferenceExtractor,
                                 ShoppingPreferencePromptRenderer shoppingPreferencePromptRenderer,
                                 BuiltInTools builtInTools) {
        this.intentRouter = intentRouter;
        this.mallMcpContextClient = mallMcpContextClient;
        this.simpleTaskAgent = simpleTaskAgent;
        this.taskPolicyRegistry = taskPolicyRegistry;
        this.shoppingStateService = shoppingStateService;
        this.shoppingPreferenceExtractor = shoppingPreferenceExtractor;
        this.shoppingPreferencePromptRenderer = shoppingPreferencePromptRenderer;
        this.builtInTools = builtInTools;
        this.tracing = new RagTracing();
    }

    RoutedAgentRequest routeBeforeCore(String userId,
                                       String sessionId,
                                       String userMessage,
                                       List<Media> media,
                                       String mallToken,
                                       String mallUsername,
                                       String mallPassword) {
        String normalizedMessage = normalizeMessage(userMessage);
        List<Media> safeMedia = media == null ? List.of() : media;
        if (intentRouter == null) {
            if (requiresMallMcp(null, normalizedMessage, safeMedia.size())) {
                MallMcpContextClient.MallMcpContextRegistration registration = registerMallMcpContext(
                        userId,
                        sessionId,
                        mallToken,
                        mallUsername,
                        mallPassword
                );
                if (registration != null && !registration.ok()) {
                    return new RoutedAgentRequest(normalizedMessage, safeMedia,
                            Flux.just("商城 MCP 调用失败：" + registration.message()));
                }
            }
            return new RoutedAgentRequest(appendMultimodalInstruction(normalizedMessage, safeMedia.size()), safeMedia, null);
        }

        // 路由前先注入已有偏好，让小模型能结合当前状态和近期变化识别本轮意图。
        String preferenceContextBeforeRoute = renderPreferenceContext(loadPreferenceSnapshot(userId, sessionId));
        ShoppingIntentRoute route = intentRouter.route(normalizedMessage, safeMedia, preferenceContextBeforeRoute);
        // 路由结果可能带来新的偏好槽位，合并后重新渲染给快车道或主 Agent 使用。
        String preferenceContextAfterRoute = updateAndRenderPreferenceContext(userId, sessionId, normalizedMessage, route);
        List<ShoppingTaskPolicy> taskPolicies = resolveTaskPolicies(route);
        boolean mallToolsAllowedByPolicy = allowsMallToolsByPolicy(taskPolicies);
        boolean simpleTaskAllowed = shouldRunSimpleTask(route)
                && (!"SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType()) || mallToolsAllowedByPolicy);
        boolean mallContextRegistered = false;

        if (simpleTaskAllowed && "SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType())) {
            MallMcpContextClient.MallMcpContextRegistration registration = registerMallMcpContext(
                    userId,
                    sessionId,
                    mallToken,
                    mallUsername,
                    mallPassword
            );
            if (registration != null && !registration.ok()) {
                return new RoutedAgentRequest(normalizedMessage, safeMedia,
                        Flux.just("商城 MCP 调用失败：" + registration.message()));
            }
            mallContextRegistered = true;
        }

        if (simpleTaskAllowed) {
            FastLaneResult simpleTaskResult = simpleTaskAgent.tryRun(
                    route,
                    normalizedMessage,
                    sessionId,
                    confidenceThreshold(),
                    preferenceContextAfterRoute
            );
            if (simpleTaskResult.handled()) {
                return new RoutedAgentRequest(normalizedMessage, safeMedia, simpleTaskResult.stream());
            }
        }

        if (!mallContextRegistered && mallToolsAllowedByPolicy && requiresMallMcp(route, normalizedMessage, safeMedia.size())) {
            MallMcpContextClient.MallMcpContextRegistration registration = registerMallMcpContext(
                    userId,
                    sessionId,
                    mallToken,
                    mallUsername,
                    mallPassword
            );
            if (registration != null && !registration.ok()) {
                return new RoutedAgentRequest(normalizedMessage, safeMedia,
                        Flux.just("商城 MCP 调用失败：" + registration.message()));
            }
        }
        boolean mallToolsAllowedForCore = mallToolsAllowedByPolicy && allowMallToolsForCore(route, normalizedMessage, safeMedia.size());
        String coreAgentMessage = buildCoreAgentMessage(normalizedMessage, safeMedia.size());
        String trustedContext = buildTrustedContext(
                preferenceContextAfterRoute,
                maybePreRetrieveKnowledge(route, normalizedMessage)
        );
        return new RoutedAgentRequest(
                coreAgentMessage,
                resolveCoreMedia(route, safeMedia),
                null,
                mallToolsAllowedForCore,
                taskPolicies,
                isOrderCreationAllowed(route, normalizedMessage),
                trustedContext
        );
    }

    /**
     * 加载短期偏好快照。相关组件未启用时返回空快照，保持路由链路可降级运行。
     */
    private ShoppingPreferenceSnapshot loadPreferenceSnapshot(String userId, String sessionId) {
        if (shoppingStateService == null) {
            return new ShoppingPreferenceSnapshot(new ShoppingPreferenceState(), List.of());
        }
        ShoppingPreferenceSnapshot snapshot = shoppingStateService.loadPreferenceSnapshot(userId, sessionId);
        return snapshot == null
                ? new ShoppingPreferenceSnapshot(new ShoppingPreferenceState(), List.of())
                : snapshot;
    }

    private String updateAndRenderPreferenceContext(String userId,
                                                    String sessionId,
                                                    String normalizedMessage,
                                                    ShoppingIntentRoute route) {
        if (shoppingStateService != null && shoppingPreferenceExtractor != null) {
            ShoppingStateService.ShoppingPreferencePatch patch =
                    shoppingPreferenceExtractor.extract(normalizedMessage, route, null);
            if (shouldMergePreferencePatch(patch)) {
                shoppingStateService.mergePreference(userId, sessionId, patch);
            }
            else if (isLowConfidenceAutomaticPatch(patch)) {
                ShoppingStateService.ShoppingPreferencePatch textPatch =
                        shoppingPreferenceExtractor.extract(normalizedMessage, null, null);
                if (hasPositiveConfidence(textPatch)) {
                    shoppingStateService.mergePreference(userId, sessionId, textPatch);
                }
            }
        }
        return renderPreferenceContext(loadPreferenceSnapshot(userId, sessionId));
    }

    private boolean shouldMergePreferencePatch(ShoppingStateService.ShoppingPreferencePatch patch) {
        if (!hasPositiveConfidence(patch)) {
            return false;
        }
        return !isAutomaticPreferencePatch(patch) || patch.confidence() >= confidenceThreshold();
    }

    private boolean isLowConfidenceAutomaticPatch(ShoppingStateService.ShoppingPreferencePatch patch) {
        return hasPositiveConfidence(patch)
                && isAutomaticPreferencePatch(patch)
                && patch.confidence() < confidenceThreshold();
    }

    private boolean isAutomaticPreferencePatch(ShoppingStateService.ShoppingPreferencePatch patch) {
        return patch != null
                && (ShoppingPreferenceSource.ROUTER_SLOT.name().equals(patch.source())
                || ShoppingPreferenceSource.VISUAL_CONTEXT.name().equals(patch.source()));
    }

    private boolean hasPositiveConfidence(ShoppingStateService.ShoppingPreferencePatch patch) {
        return patch != null && patch.confidence() != null && patch.confidence() > 0.0;
    }

    private String renderPreferenceContext(ShoppingPreferenceSnapshot snapshot) {
        if (shoppingPreferencePromptRenderer == null) {
            return "";
        }
        String rendered = shoppingPreferencePromptRenderer.render(snapshot);
        return StringUtils.hasText(rendered) ? rendered : "";
    }

    private boolean isOrderCreationAllowed(ShoppingIntentRoute route, String message) {
        return route != null
                && route.isHighConfidence(confidenceThreshold())
                && "CART_CONFIRMATION".equals(route.normalizedIntent())
                && Boolean.TRUE.equals(route.needConfirm())
                && isExplicitOrderCreationText(message);
    }

    private List<ShoppingTaskPolicy> resolveTaskPolicies(ShoppingIntentRoute route) {
        if (taskPolicyRegistry == null) {
            return List.of();
        }
        return taskPolicyRegistry.resolve(route);
    }

    private boolean allowsMallToolsByPolicy(List<ShoppingTaskPolicy> taskPolicies) {
        if (taskPolicies == null || taskPolicies.isEmpty()) {
            return true;
        }
        java.util.Set<String> allowedToolNames = taskPolicies.stream()
                .flatMap(policy -> policy.allowedToolNames().stream())
                .collect(java.util.stream.Collectors.toSet());
        return allowedToolNames.isEmpty() || allowedToolNames.stream().anyMatch(MallMcpToolCallback::isMallTool);
    }

    private boolean requiresMallMcp(ShoppingIntentRoute route, String message, int mediaCount) {
        if (route != null && route.isHighConfidence(confidenceThreshold())) {
            if ("FAQ_SIMPLE_QUERY".equals(route.normalizedTaskType())) {
                return false;
            }
            if ("SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType())) {
                return true;
            }
            return switch (route.normalizedIntent()) {
                case "PRODUCT_SELECTION", "CART_CONFIRMATION" -> true;
                default -> false;
            };
        }
        if (mediaCount > 0) {
            return hasExplicitRealtimeMallKeyword(message);
        }
        return StringUtils.hasText(message) && MALL_KEYWORD_TEXT.matcher(message.trim()).matches();
    }

    private boolean allowMallToolsForCore(ShoppingIntentRoute route, String message, int mediaCount) {
        if (route != null && route.isHighConfidence(confidenceThreshold())) {
            if ("FAQ_SIMPLE_QUERY".equals(route.normalizedTaskType())) {
                return false;
            }
            if ("SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType())) {
                return true;
            }
            return hasRealtimeMallNeed(route, message);
        }
        return requiresMallMcp(route, message, mediaCount);
    }

    private boolean hasRealtimeMallNeed(ShoppingIntentRoute route, String message) {
        String normalizedIntent = route == null ? "UNKNOWN" : route.normalizedIntent();
        if (switch (normalizedIntent) {
            case "PRODUCT_SELECTION", "CART_CONFIRMATION" -> true;
            default -> false;
        }) {
            return true;
        }
        String normalizedMessage = StringUtils.hasText(message) ? message.trim() : "";
        if (StringUtils.hasText(normalizedMessage) && HARD_MALL_OPERATION_TEXT.matcher(normalizedMessage).matches()) {
            return true;
        }
        if (hasSpecificSkuSlot(route)) {
            return true;
        }
        if (StringUtils.hasText(normalizedMessage) && NO_REALTIME_MALL_TOOL_TEXT.matcher(normalizedMessage).matches()) {
            return false;
        }
        return StringUtils.hasText(normalizedMessage) && REALTIME_MALL_TOOL_TEXT.matcher(normalizedMessage).matches();
    }

    private boolean hasExplicitRealtimeMallKeyword(String message) {
        String normalizedMessage = StringUtils.hasText(message) ? message.trim() : "";
        if (!StringUtils.hasText(normalizedMessage) || NO_REALTIME_MALL_TOOL_TEXT.matcher(normalizedMessage).matches()) {
            return false;
        }
        return HARD_MALL_OPERATION_TEXT.matcher(normalizedMessage).matches()
                || REALTIME_MALL_TOOL_TEXT.matcher(normalizedMessage).matches();
    }

    private boolean isExplicitOrderCreationText(String message) {
        String normalizedMessage = StringUtils.hasText(message) ? message.trim() : "";
        return StringUtils.hasText(normalizedMessage)
                && ORDER_CREATION_TEXT.matcher(normalizedMessage).matches();
    }

    private boolean hasSpecificSkuSlot(ShoppingIntentRoute route) {
        if (route == null || route.textSlots().isEmpty()) {
            return false;
        }
        return route.textSlots().keySet().stream()
                .map(key -> key == null ? "" : key.trim().toLowerCase())
                .anyMatch(key -> key.contains("sku") || key.contains("product_id") || key.contains("productid"));
    }

    private boolean shouldRunSimpleTask(ShoppingIntentRoute route) {
        return simpleTaskAgent != null
                && route != null
                && route.isHighConfidence(confidenceThreshold())
                && !Boolean.TRUE.equals(route.routeToCore())
                && ("FAQ_SIMPLE_QUERY".equals(route.normalizedTaskType())
                || "SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType()));
    }

    private MallMcpContextClient.MallMcpContextRegistration registerMallMcpContext(String userId,
                                                                                   String sessionId,
                                                                                   String mallToken,
                                                                                   String mallUsername,
                                                                                   String mallPassword) {
        if (mallMcpContextClient == null) {
            return MallMcpContextClient.MallMcpContextRegistration.failed("mall-mcp context client unavailable");
        }
        return mallMcpContextClient.register(userId, sessionId, mallToken, mallUsername, mallPassword);
    }

    private String buildCoreAgentMessage(String message, int mediaCount) {
        return appendMultimodalInstruction(message, mediaCount);
    }

    private String buildTrustedContext(String preferenceContext, String knowledgeContext) {
        StringBuilder builder = new StringBuilder();
        appendTrustedContextSection(builder, preferenceContext);
        appendTrustedContextSection(builder, knowledgeContext);
        return builder.toString();
    }

    private void appendTrustedContextSection(StringBuilder builder, String section) {
        if (!StringUtils.hasText(section)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(System.lineSeparator()).append(System.lineSeparator());
        }
        builder.append(section.trim());
    }

    private String maybePreRetrieveKnowledge(ShoppingIntentRoute route, String message) {
        if (!shouldPreRetrieveKnowledge(route, message)) {
            setTraceAttribute("rag.pre_retrieval.required", false);
            return "";
        }
        setTraceAttribute("rag.pre_retrieval.required", true);

        try {
            String knowledge = builtInTools == null ? "" : builtInTools.searchProductKnowledge(message);
            if (!StringUtils.hasText(knowledge) || knowledge.contains(EMPTY_KNOWLEDGE_MESSAGE)) {
                setTraceAttribute("rag.pre_retrieval.hit", false);
                return "";
            }
            setTraceAttribute("rag.pre_retrieval.hit", true);
            setTraceAttribute("rag.pre_retrieval.context_length", knowledge.length());
            return """
                    商品知识库预检索候选：
                    %s

                    使用规则：
                    - 以上候选是本轮唯一可推荐商品池。
                    - 推荐、选哪个、怎么选、对比和组合题必须从以上候选中给结论。
                    - 禁止推荐候选外商品；候选全不适合时逐个说明不适合原因。
                    """.formatted(knowledge.trim()).trim();
        }
        catch (RuntimeException ex) {
            setTraceAttribute("rag.pre_retrieval.hit", false);
            setTraceAttribute("rag.pre_retrieval.error", ex.getClass().getSimpleName());
            return "";
        }
    }

    private void setTraceAttribute(String key, boolean value) {
        RagTracing activeTracing = tracing == null ? new RagTracing() : tracing;
        Span span = activeTracing.currentSpan();
        activeTracing.setAttribute(span, key, value);
    }

    private void setTraceAttribute(String key, long value) {
        RagTracing activeTracing = tracing == null ? new RagTracing() : tracing;
        Span span = activeTracing.currentSpan();
        activeTracing.setAttribute(span, key, value);
    }

    private void setTraceAttribute(String key, String value) {
        RagTracing activeTracing = tracing == null ? new RagTracing() : tracing;
        Span span = activeTracing.currentSpan();
        activeTracing.setAttribute(span, key, value);
    }

    private boolean shouldPreRetrieveKnowledge(ShoppingIntentRoute route, String message) {
        return builtInTools != null
                && route != null
                && route.isHighConfidence(confidenceThreshold())
                && "COMPLEX_REACT".equals(route.normalizedTaskType())
                && isShoppingKnowledgeRequest(route, message);
    }

    private boolean isShoppingKnowledgeRequest(ShoppingIntentRoute route, String message) {
        if (switch (route.normalizedIntent()) {
            case "PRODUCT_SELECTION", "PRODUCT_COMPARE", "RECOMMENDATION" -> true;
            default -> false;
        }) {
            return true;
        }
        String normalizedMessage = StringUtils.hasText(message) ? message.trim() : "";
        return StringUtils.hasText(normalizedMessage)
                && PRE_RETRIEVAL_ACTION_TEXT.matcher(normalizedMessage).matches()
                && PRE_RETRIEVAL_PRODUCT_TEXT.matcher(normalizedMessage).matches();
    }

    private List<Media> resolveCoreMedia(ShoppingIntentRoute route, List<Media> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }
        if (route != null && route.isHighConfidence(confidenceThreshold())
                && (route.hasVisualContext() || "RECOMMENDATION".equals(route.normalizedIntent()))) {
            return List.of();
        }
        return media;
    }

    private String normalizeMessage(String message) {
        return StringUtils.hasText(message) ? message.trim() : "请帮我看看这件商品适合什么场景";
    }

    private double confidenceThreshold() {
        return confidenceThreshold;
    }

    private String appendMultimodalInstruction(String message, int mediaCount) {
        String normalizedMessage = StringUtils.hasText(message) ? message.trim() : "请基于图片帮我推荐相似商品";
        if (mediaCount <= 0) {
            return normalizedMessage;
        }
        return normalizedMessage + System.lineSeparator()
                + "用户同时上传了 " + mediaCount + " 张商品图片。请先理解图片中的品类、颜色、风格、材质和显著规格，再结合文本需求调用商品检索、相似款、价格库存或购物车工具。";
    }
}
