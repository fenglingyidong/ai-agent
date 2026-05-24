package com.example.ragagent.service;

import com.example.ragagent.mall.MallMcpContextClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ShoppingRouteExecutor {

    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.7;
    private static final Pattern MALL_KEYWORD_TEXT = Pattern.compile(".*(商品|价格|多少钱|库存|SKU|购物车|加购|加入购物车|下单|订单|确认订单|买|相似款|推荐).*");
    private static final Pattern HARD_MALL_OPERATION_TEXT = Pattern.compile(".*(购物车|加购|加入购物车|下单|订单|确认订单|创建订单|购买这|买这|买它).*");
    private static final Pattern REALTIME_MALL_TOOL_TEXT = Pattern.compile(".*(实时|价格|多少钱|库存|现货|SKU|sku|商品详情|详情|评价|优惠|促销|这款|这个商品|相似款).*");
    private static final Pattern NO_REALTIME_MALL_TOOL_TEXT = Pattern.compile(".*(不需要|不用|无需|不要).*?(实时|价格|库存|商城|搜索|查价).*");

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

        ShoppingIntentRoute route = intentRouter.route(normalizedMessage, safeMedia);
        List<ShoppingTaskPolicy> taskPolicies = resolveTaskPolicies(route);
        boolean mallToolsAllowedByPolicy = allowsMallToolsByPolicy(taskPolicies);
        boolean simpleTaskAllowed = shouldRunSimpleTask(route)
                && (!"B_SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType()) || mallToolsAllowedByPolicy);
        String fastLaneFallbackReason = "";
        boolean mallContextRegistered = false;

        if (simpleTaskAllowed && "B_SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType())) {
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
                    confidenceThreshold()
            );
            if (simpleTaskResult.handled()) {
                return new RoutedAgentRequest(normalizedMessage, safeMedia, simpleTaskResult.stream());
            }
            if (simpleTaskResult.fallbackToCore()) {
                fastLaneFallbackReason = simpleTaskResult.fallbackReason();
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
        return new RoutedAgentRequest(
                buildCoreAgentMessage(normalizedMessage, route, safeMedia.size(), fastLaneFallbackReason, taskPolicies),
                resolveCoreMedia(route, safeMedia),
                null,
                mallToolsAllowedByPolicy && allowMallToolsForCore(route, normalizedMessage, safeMedia.size()),
                taskPolicies,
                isOrderCreationAllowed(route)
        );
    }

    private boolean isOrderCreationAllowed(ShoppingIntentRoute route) {
        return route != null
                && route.isHighConfidence(confidenceThreshold())
                && "CREATE_ORDER".equals(route.normalizedIntent())
                && Boolean.TRUE.equals(route.needConfirm());
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
            if ("A_FAQ_SIMPLE_QUERY".equals(route.normalizedTaskType())) {
                return false;
            }
            if ("B_SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType())) {
                return true;
            }
            return switch (route.normalizedIntent()) {
                case "QUERY_ATTRIBUTE", "PRICE_STOCK_QUERY", "FIND_SIMILAR", "VIEW_CART", "ADD_TO_CART", "PREPARE_ORDER" -> true;
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
            if ("A_FAQ_SIMPLE_QUERY".equals(route.normalizedTaskType())) {
                return false;
            }
            if ("B_SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType())) {
                return true;
            }
            return hasRealtimeMallNeed(route, message);
        }
        return requiresMallMcp(route, message, mediaCount);
    }

    private boolean hasRealtimeMallNeed(ShoppingIntentRoute route, String message) {
        String normalizedIntent = route == null ? "UNKNOWN" : route.normalizedIntent();
        if (switch (normalizedIntent) {
            case "QUERY_ATTRIBUTE", "PRICE_STOCK_QUERY", "FIND_SIMILAR", "VIEW_CART",
                    "ADD_TO_CART", "PREPARE_ORDER", "CREATE_ORDER" -> true;
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
                && ("A_FAQ_SIMPLE_QUERY".equals(route.normalizedTaskType())
                || "B_SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType()));
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

    private String buildCoreAgentMessage(String message, ShoppingIntentRoute route, int mediaCount) {
        return buildCoreAgentMessage(message, route, mediaCount, "");
    }

    private String buildCoreAgentMessage(String message, ShoppingIntentRoute route, int mediaCount, String fastLaneFallbackReason) {
        return buildCoreAgentMessage(message, route, mediaCount, fastLaneFallbackReason, List.of());
    }

    private String buildCoreAgentMessage(String message,
                                         ShoppingIntentRoute route,
                                         int mediaCount,
                                         String fastLaneFallbackReason,
                                         List<ShoppingTaskPolicy> taskPolicies) {
        if (route != null && route.isHighConfidence(confidenceThreshold())) {
            return """
                    观察到的任务类型：%s
                    观察到的用户意图：%s
                    路由置信度：%.2f
                    快车道降级原因：%s
                    Planner 选择策略：%s
                    缺失槽位：%s
                    候选工具：%s
                    是否需要确认：%s
                    风险等级：%s
                    视觉提取结果：%s
                    文本槽位：%s
                    用户原话：%s
                    """.formatted(
                    route.normalizedTaskType(),
                    route.normalizedIntent(),
                    route.confidence(),
                    StringUtils.hasText(fastLaneFallbackReason) ? fastLaneFallbackReason : "无",
                    renderPolicyIds(taskPolicies),
                    renderTextList(route.missingSlots()),
                    renderTextList(route.toolCandidates()),
                    route.needConfirm() ? "是" : "否",
                    route.riskLevel(),
                    renderSlots(route.visualContext()),
                    renderSlots(route.textSlots()),
                    message
            ).trim();
        }
        return appendMultimodalInstruction(message, mediaCount);
    }

    private List<Media> resolveCoreMedia(ShoppingIntentRoute route, List<Media> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }
        if (route != null && route.isHighConfidence(confidenceThreshold())
                && (route.hasVisualContext() || "COMPLEX_RECOMMENDATION".equals(route.normalizedIntent()))) {
            return List.of();
        }
        return media;
    }

    private void appendSlotParts(List<String> parts, Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        slots.forEach((key, value) -> {
            String text = slotValueToText(value);
            if (StringUtils.hasText(key) && StringUtils.hasText(text)) {
                parts.add(key + "=" + text);
            }
        });
    }

    private String renderSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        appendSlotParts(parts, slots);
        return parts.isEmpty() ? "无" : String.join("，", parts);
    }

    private String renderPolicyIds(List<ShoppingTaskPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            return "无";
        }
        return policies.stream()
                .map(ShoppingTaskPolicy::id)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String renderTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        String rendered = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(", "));
        return StringUtils.hasText(rendered) ? rendered : "无";
    }

    private String slotValueToText(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString().trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
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
