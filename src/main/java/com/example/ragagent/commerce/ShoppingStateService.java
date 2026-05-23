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

    public ShoppingPreferenceState loadPreference(String userId, String sessionId) {
        String key = preferenceKey(userId, sessionId);
        String raw = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(raw)) {
            return new ShoppingPreferenceState();
        }
        try {
            return objectMapper.readValue(raw, ShoppingPreferenceState.class);
        }
        catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize shopping preference state. key={}, reason={}", key, ex.getOriginalMessage());
            return new ShoppingPreferenceState();
        }
    }

    public ShoppingPreferenceState mergePreference(String userId,
                                                   String sessionId,
                                                   @NotNull @Valid ShoppingPreferencePatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        ShoppingPreferenceState state = loadPreference(userId, sessionId);
        if (StringUtils.hasText(patch.category())) {
            state.setCategory(patch.category().trim());
        }
        if (patch.budgetMin() != null) {
            state.setBudgetMin(patch.budgetMin());
        }
        if (patch.budgetMax() != null) {
            state.setBudgetMax(patch.budgetMax());
        }
        if (StringUtils.hasText(patch.brand())) {
            state.setBrand(patch.brand().trim());
        }
        if (StringUtils.hasText(patch.size())) {
            state.setSize(patch.size().trim());
        }
        if (StringUtils.hasText(patch.color())) {
            state.setColor(patch.color().trim());
        }
        if (StringUtils.hasText(patch.style())) {
            state.setStyle(patch.style().trim());
        }
        if (StringUtils.hasText(patch.usageScenario())) {
            state.setUsageScenario(patch.usageScenario().trim());
        }
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
            String usageScenario
    ) {
    }
}
