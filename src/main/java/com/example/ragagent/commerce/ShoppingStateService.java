package com.example.ragagent.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class ShoppingStateService {

    private static final Duration STATE_TTL = Duration.ofDays(7);
    private static final String PREFERENCE_PREFIX = "shopping:preference:";
    private static final String CART_PREFIX = "shopping:cart:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public ShoppingStateService() {
    }

    public ShoppingStateService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public ShoppingPreferenceState loadPreference(String userId, String sessionId) {
        String raw = redisTemplate.opsForValue().get(preferenceKey(userId, sessionId));
        if (!StringUtils.hasText(raw)) {
            return new ShoppingPreferenceState();
        }
        try {
            return objectMapper.readValue(raw, ShoppingPreferenceState.class);
        }
        catch (JsonProcessingException ex) {
            return new ShoppingPreferenceState();
        }
    }

    public ShoppingPreferenceState mergePreference(String userId,
                                                   String sessionId,
                                                   ShoppingPreferencePatch patch) {
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
        savePreference(userId, sessionId, state);
        return state;
    }

    public Map<String, Integer> loadCart(String userId, String sessionId) {
        Map<Object, Object> rawCart = redisTemplate.opsForHash().entries(cartKey(userId, sessionId));
        Map<String, Integer> cart = new LinkedHashMap<>();
        rawCart.forEach((key, value) -> {
            int quantity = parseQuantity(value);
            if (quantity > 0) {
                cart.put(key.toString(), quantity);
            }
        });
        return cart;
    }

    public Map<String, Integer> addToCart(String userId, String sessionId, String skuId, int quantity) {
        String key = cartKey(userId, sessionId);
        redisTemplate.opsForHash().increment(key, skuId, Math.max(1, quantity));
        redisTemplate.expire(key, STATE_TTL);
        return loadCart(userId, sessionId);
    }

    public Map<String, Integer> setCartItemQuantity(String userId, String sessionId, String skuId, int quantity) {
        String key = cartKey(userId, sessionId);
        if (quantity <= 0) {
            redisTemplate.opsForHash().delete(key, skuId);
        }
        else {
            redisTemplate.opsForHash().put(key, skuId, String.valueOf(quantity));
            redisTemplate.expire(key, STATE_TTL);
        }
        return loadCart(userId, sessionId);
    }

    public Map<String, Integer> removeCartItem(String userId, String sessionId, String skuId) {
        redisTemplate.opsForHash().delete(cartKey(userId, sessionId), skuId);
        return loadCart(userId, sessionId);
    }

    private void savePreference(String userId, String sessionId, ShoppingPreferenceState state) {
        try {
            redisTemplate.opsForValue().set(preferenceKey(userId, sessionId), objectMapper.writeValueAsString(state), STATE_TTL);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize shopping preference state", ex);
        }
    }

    private int parseQuantity(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String preferenceKey(String userId, String sessionId) {
        return PREFERENCE_PREFIX + normalize(userId) + ":" + normalize(sessionId);
    }

    private String cartKey(String userId, String sessionId) {
        return CART_PREFIX + normalize(userId) + ":" + normalize(sessionId);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "default";
    }

    public record ShoppingPreferencePatch(
            String category,
            Integer budgetMin,
            Integer budgetMax,
            String brand,
            String size,
            String color,
            String style,
            String usageScenario
    ) {
    }
}
