package com.example.ragagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ragagent.observability.RagTracing;
import com.example.ragagent.prompt.PromptTemplateStore;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class ShoppingIntentRouter {

    private static final Logger log = LoggerFactory.getLogger(ShoppingIntentRouter.class);
    private static final double SIMPLE_ROUTE_CONFIDENCE_THRESHOLD = 0.7;
    private static final ObjectMapper TRACE_OBJECT_MAPPER = new ObjectMapper();

    private final String routerSystemPrompt;
    private final String routerVisualSystemPrompt;

    @Autowired
    private ChatClient.Builder builder;

    @Value("${app.ai.intent-router.enabled:true}")
    private boolean enabled = true;

    @Value("${app.ai.intent-router.model:qwen3-vl-8b-instruct}")
    private String model = "qwen3-vl-8b-instruct";

    @Autowired(required = false)
    private RagTracing tracing;

    private ChatClient routerChatClient;

    public ShoppingIntentRouter() {
        this(null, new RagTracing(), new PromptTemplateStore());
    }

    public ShoppingIntentRouter(ChatClient routerChatClient) {
        this(routerChatClient, new RagTracing(), new PromptTemplateStore());
    }

    ShoppingIntentRouter(ChatClient routerChatClient, RagTracing tracing) {
        this(routerChatClient, tracing, new PromptTemplateStore());
    }

    ShoppingIntentRouter(ChatClient routerChatClient, RagTracing tracing, PromptTemplateStore promptTemplateStore) {
        this.routerChatClient = routerChatClient;
        this.tracing = tracing == null ? new RagTracing() : tracing;
        PromptTemplateStore store = promptTemplateStore == null ? new PromptTemplateStore() : promptTemplateStore;
        this.routerSystemPrompt = store.text("intent-router.system");
        this.routerVisualSystemPrompt = store.text("intent-router.visual.system");
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (routerChatClient == null && builder != null) {
            this.routerChatClient = builder.clone().build();
        }
    }

    public ShoppingIntentRoute route(String userMessage, List<Media> media) {
        return route(userMessage, media, "");
    }

    public ShoppingIntentRoute route(String userMessage, List<Media> media, String preferenceContext) {
        if (!enabled) {
            return ShoppingIntentRoute.fallback("intent router disabled");
        }
        if (routerChatClient == null) {
            return ShoppingIntentRoute.fallback("intent router chat client unavailable");
        }

        String normalizedMessage = StringUtils.hasText(userMessage) ? userMessage.trim() : "";
        List<Media> safeMedia = media == null ? List.of() : media;
        return tracing().inSpan("llm.intent_router", () -> routeInCurrentSpan(normalizedMessage, safeMedia, preferenceContext));
    }

    private ShoppingIntentRoute routeInCurrentSpan(String normalizedMessage,
                                                  List<Media> safeMedia,
                                                  String preferenceContext) {
        try {
            RagTracing activeTracing = tracing();
            Span span = activeTracing.currentSpan();
            String systemPrompt = buildSystemPrompt(safeMedia.size());
            ChatClient.ChatClientRequestSpec requestSpec = routerChatClient.prompt()
                    .options(buildOptions())
                    .system(systemPrompt);
            String userPrompt = buildUserPrompt(normalizedMessage, safeMedia.size(), preferenceContext);
            activeTracing.capturePromptText(span, "llm.intent_router.input", llmInput(systemPrompt, userPrompt));
            if (safeMedia.isEmpty()) {
                requestSpec = requestSpec.user(userPrompt);
            }
            else {
                requestSpec = requestSpec.user(user -> user
                        .text(userPrompt)
                        .media(safeMedia.toArray(Media[]::new)));
            }

            ShoppingIntentRoute route = normalizeFastLaneRoute(toLightweightRoute(
                    requestSpec.call().entity(ShoppingIntentRoute.class)
            ));
            activeTracing.capturePromptText(span, "llm.intent_router.output", routeTraceJson(route));
            if (route == null) {
                return ShoppingIntentRoute.fallback("intent router returned empty route");
            }
            log.info("购物意图路由完成：taskType={}, intent={}, routeToCore={}, confidence={}, reason={}",
                    route.normalizedTaskType(), route.normalizedIntent(), route.routeToCore(), route.confidence(), route.reason());
            return route;
        }
        catch (Exception ex) {
            log.warn("购物意图路由失败，回退主 Agent：{}", ex.getMessage());
            log.debug("购物意图路由异常堆栈", ex);
            return ShoppingIntentRoute.fallback("intent router failed: " + ex.getMessage());
        }
    }

    private OpenAiChatOptions buildOptions() {
        return OpenAiChatOptions.builder()
                .model(StringUtils.hasText(model) ? model.trim() : "qwen3-vl-8b-instruct")
                .temperature(0.0)
                .maxTokens(700)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .build();
    }

    private String buildUserPrompt(String userMessage, int mediaCount) {
        return buildUserPrompt(userMessage, mediaCount, "");
    }

    private String buildSystemPrompt(int mediaCount) {
        if (mediaCount <= 0 || !StringUtils.hasText(routerVisualSystemPrompt)) {
            return routerSystemPrompt;
        }
        return routerSystemPrompt + "\n\n" + routerVisualSystemPrompt;
    }

    private String buildUserPrompt(String userMessage, int mediaCount, String preferenceContext) {
        String prompt = """
                用户本轮输入：
                <user_input>
                %s
                </user_input>

                上传图片数量：%d
                请结合文本%s输出路由 JSON。
                """.formatted(
                StringUtils.hasText(userMessage) ? userMessage : "请帮我看看这件商品适合什么场景",
                mediaCount,
                mediaCount > 0 ? "和图片" : ""
        );
        if (!StringUtils.hasText(preferenceContext)) {
            return prompt;
        }
        return preferenceContext.trim() + "\n\n" + prompt;
    }

    private ShoppingIntentRoute normalizeFastLaneRoute(ShoppingIntentRoute route) {
        if (route == null || !route.isHighConfidence(SIMPLE_ROUTE_CONFIDENCE_THRESHOLD)
                || !isFastLaneTask(route)
                || !Boolean.TRUE.equals(route.routeToCore())) {
            return route;
        }
        return new ShoppingIntentRoute(
                route.intent(),
                route.normalizedTaskType(),
                route.visualContext(),
                route.textSlots(),
                route.preferenceDelta(),
                false,
                route.confidence(),
                appendReason(route.reason(), "高置信简单意图按规则进入短路候选"),
                route.taskPolicies(),
                route.missingSlots(),
                route.toolCandidates(),
                route.needConfirm(),
                route.riskLevel()
        );
    }

    private ShoppingIntentRoute toLightweightRoute(ShoppingIntentRoute route) {
        if (route == null) {
            return null;
        }
        return new ShoppingIntentRoute(
                route.normalizedIntent(),
                route.normalizedTaskType(),
                route.visualContext(),
                Map.of(),
                route.preferenceDelta(),
                null,
                route.confidence(),
                route.reason(),
                List.of(),
                List.of(),
                List.of(),
                false,
                "LOW"
        );
    }

    private boolean isFastLaneTask(ShoppingIntentRoute route) {
        if ("FAQ_SIMPLE_QUERY".equals(route.normalizedTaskType())) {
            return true;
        }
        return "SIMPLE_SHOPPING_TOOL".equals(route.normalizedTaskType()) && switch (route.normalizedIntent()) {
            case "PRODUCT_SELECTION", "CART_CONFIRMATION" -> true;
            default -> false;
        };
    }

    private String appendReason(String reason, String extra) {
        if (!StringUtils.hasText(reason)) {
            return extra;
        }
        return reason.trim() + "；" + extra;
    }

    private String routeTraceJson(ShoppingIntentRoute route) {
        if (route == null) {
            return "null";
        }
        Map<String, Object> trace = Map.of(
                "intent", route.normalizedIntent(),
                "task_type", route.normalizedTaskType(),
                "visual_context", route.visualContext(),
                "preference_delta", route.preferenceDelta(),
                "confidence", route.confidence(),
                "reason", route.reason()
        );
        try {
            return TRACE_OBJECT_MAPPER.writeValueAsString(trace);
        }
        catch (JsonProcessingException ex) {
            return String.valueOf(trace);
        }
    }

    private RagTracing tracing() {
        if (tracing == null) {
            tracing = new RagTracing();
        }
        return tracing;
    }

    private String llmInput(String systemPrompt, String userPrompt) {
        return "system:\n" + systemPrompt + "\n\nuser:\n" + userPrompt;
    }
}
