package com.example.ragagent.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

@Service
@Validated
public class ShoppingStateService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingStateService.class);

    private static final String PREFERENCE_PREFIX = "shopping:preference:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.shopping.preference-ttl:7d}")
    private Duration preferenceTtl = Duration.ofDays(7);

    @Autowired
    private ShoppingPreferenceMergePolicy mergePolicy;

    public ShoppingPreferenceState loadPreference(String userId, String sessionId) {
        String key = preferenceKey(userId, sessionId);
        String raw = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(raw)) {
            return new ShoppingPreferenceState();
        }
        try {
            ShoppingPreferenceState state = objectMapper.readValue(raw, ShoppingPreferenceState.class);
            return state == null ? new ShoppingPreferenceState() : state;
        }
        catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize shopping preference state. key={}, reason={}", key, ex.getOriginalMessage());
            return new ShoppingPreferenceState();
        }
    }

    // 单实例内串行化 Redis 读-改-写，降低自动偏好写入时的字段丢失风险；Redis TTL/持久化策略保持不变。
    public synchronized ShoppingPreferenceState mergePreference(String userId,
                                                                String sessionId,
                                                                @NotNull @Valid ShoppingPreferencePatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        ShoppingPreferenceState state = mergePolicy.merge(loadPreference(userId, sessionId), patch);
        validateBudgetRange(state);
        savePreference(userId, sessionId, state);
        return state;
    }

    private void savePreference(String userId, String sessionId, ShoppingPreferenceState state) {
        try {
            redisTemplate.opsForValue().set(preferenceKey(userId, sessionId), objectMapper.writeValueAsString(state), preferenceTtl);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize shopping preference state", ex);
        }
    }

    private void validateBudgetRange(ShoppingPreferenceState state) {
        if (state.getBudgetMin() != null && state.getBudgetMax() != null
                && state.getBudgetMin() > state.getBudgetMax()) {
            throw new IllegalArgumentException("budgetMin must be less than or equal to budgetMax");
        }
    }

    private String preferenceKey(String userId, String sessionId) {
        return PREFERENCE_PREFIX + normalize(userId) + ":" + normalize(sessionId);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "default";
    }

    public record ShoppingPreferencePatch(
            @Size(max = 64)
            String category,
            @PositiveOrZero
            Integer budgetMin,
            @PositiveOrZero
            Integer budgetMax,
            @Size(max = 64)
            String brand,
            @Size(max = 64)
            String size,
            @Size(max = 64)
            String color,
            @Size(max = 64)
            String style,
            @Size(max = 128)
            String usageScenario,
            Set<String> clearFields,
            String source,
            Double confidence,
            Long turnNo
    ) {
        public ShoppingPreferencePatch(String category,
                                       Integer budgetMin,
                                       Integer budgetMax,
                                       String brand,
                                       String size,
                                       String color,
                                       String style,
                                       String usageScenario) {
            this(
                    category,
                    budgetMin,
                    budgetMax,
                    brand,
                    size,
                    color,
                    style,
                    usageScenario,
                    Set.of(),
                    ShoppingPreferenceSource.USER_EXPLICIT.name(),
                    1.0,
                    null
            );
        }

        public ShoppingPreferencePatch {
            clearFields = clearFields == null ? Set.of() : Set.copyOf(clearFields);
            source = normalizeSource(source);
            confidence = clampConfidence(confidence);
        }

        private static String normalizeSource(String source) {
            if (!StringUtils.hasText(source)) {
                return ShoppingPreferenceSource.USER_EXPLICIT.name();
            }
            try {
                return ShoppingPreferenceSource.valueOf(source.trim()).name();
            }
            catch (IllegalArgumentException ex) {
                return ShoppingPreferenceSource.USER_EXPLICIT.name();
            }
        }

        private static Double clampConfidence(Double confidence) {
            if (confidence == null || !Double.isFinite(confidence)) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, confidence));
        }
    }
}
