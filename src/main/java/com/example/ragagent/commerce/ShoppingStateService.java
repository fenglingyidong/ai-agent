package com.example.ragagent.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 管理当前会话的短期导购偏好。
 *
 * <p>Redis Hash 保存当前完整状态，Redis List 保存最近若干次增量变化。
 * Hash 是读取偏好的事实源，List 只用于向 Agent 提供近期变化线索。</p>
 */
@Service
@Validated
public class ShoppingStateService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingStateService.class);

    // v2 使用新前缀，与旧版整段 JSON String 存储隔离，避免灰度期间误读旧结构。
    private static final String STATE_PREFIX = "shopping:preference:v2:state:";
    private static final String CHANGES_PREFIX = "shopping:preference:v2:changes:";
    private static final int RECENT_CHANGE_LIMIT = 5;
    private static final TypeReference<Map<String, Object>> DELTA_MAP_TYPE = new TypeReference<>() {
    };
    private static final List<String> PREFERENCE_FIELDS = List.of(
            "category",
            "budget",
            "brand",
            "size",
            "color",
            "style",
            "usageScenario"
    );

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.shopping.preference-ttl:7d}")
    private Duration preferenceTtl = Duration.ofDays(7);

    @Autowired
    private ShoppingPreferenceMergePolicy mergePolicy;

    /**
     * 从 Redis Hash 读取当前完整偏好，不依赖变更日志回放。
     */
    public ShoppingPreferenceState loadPreference(String userId, String sessionId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(stateKey(userId, sessionId));
        if (entries == null || entries.isEmpty()) {
            return new ShoppingPreferenceState();
        }
        return fromHash(entries);
    }

    /**
     * 读取供 Agent 上下文使用的偏好快照：当前完整状态加最近增量变化。
     */
    public ShoppingPreferenceSnapshot loadPreferenceSnapshot(String userId, String sessionId) {
        ShoppingPreferenceState state = loadPreference(userId, sessionId);
        List<String> rawChanges = redisTemplate.opsForList().range(changesKey(userId, sessionId), 0, -1);
        if (rawChanges == null || rawChanges.isEmpty()) {
            return new ShoppingPreferenceSnapshot(state, List.of());
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        for (String rawChange : rawChanges) {
            // 单条历史日志损坏不应阻断当前偏好的读取。
            if (!StringUtils.hasText(rawChange)) {
                continue;
            }
            try {
                Map<String, Object> change = objectMapper.readValue(rawChange, DELTA_MAP_TYPE);
                if (change != null && !change.isEmpty()) {
                    changes.add(change);
                }
            }
            catch (JsonProcessingException ex) {
                log.warn("Failed to deserialize shopping preference delta. reason={}", ex.getOriginalMessage());
            }
        }
        return new ShoppingPreferenceSnapshot(state, changes);
    }

    /**
     * 将本轮偏好补丁合并进 Redis 中的当前会话偏好。
     */
    // 单实例内串行化 Redis 读-改-写，降低自动偏好写入时的字段丢失风险。
    public synchronized ShoppingPreferenceState mergePreference(String userId,
                                                                String sessionId,
                                                                @NotNull @Valid ShoppingPreferencePatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        ShoppingPreferenceState before = loadPreference(userId, sessionId);
        ShoppingPreferenceState state = mergePolicy.merge(copyOf(before), patch);
        savePreference(userId, sessionId, before, state);
        return state;
    }

    private void savePreference(String userId,
                                String sessionId,
                                ShoppingPreferenceState before,
                                ShoppingPreferenceState state) {
        Map<String, Object> delta = buildDelta(before, state);
        if (delta.isEmpty()) {
            return;
        }

        // 先完成序列化，再写 Redis，避免序列化异常留下仅更新 Hash 的半成品状态。
        String deltaJson;
        try {
            deltaJson = objectMapper.writeValueAsString(delta);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize shopping preference delta", ex);
        }

        String stateKey = stateKey(userId, sessionId);
        String changesKey = changesKey(userId, sessionId);
        Map<String, String> updates = hashUpdates(delta, state);
        List<String> clearedFields = clearedFields(delta);
        if (!updates.isEmpty()) {
            redisTemplate.opsForHash().putAll(stateKey, updates);
        }
        if (!clearedFields.isEmpty()) {
            redisTemplate.opsForHash().delete(stateKey, clearedFields.toArray());
        }
        redisTemplate.opsForList().rightPush(changesKey, deltaJson);
        redisTemplate.opsForList().trim(changesKey, -RECENT_CHANGE_LIMIT, -1);
        redisTemplate.expire(stateKey, preferenceTtl);
        redisTemplate.expire(changesKey, preferenceTtl);
    }

    private ShoppingPreferenceState fromHash(Map<Object, Object> entries) {
        ShoppingPreferenceState state = new ShoppingPreferenceState();
        state.setCategory(readString(entries, "category"));
        state.setBudget(readBudget(entries));
        state.setBrand(readString(entries, "brand"));
        state.setSize(readString(entries, "size"));
        state.setColor(readString(entries, "color"));
        state.setStyle(readString(entries, "style"));
        state.setUsageScenario(readString(entries, "usageScenario"));
        state.setSource(readString(entries, "source"));
        state.setConfidence(readDouble(entries, "confidence"));
        state.setUpdatedAtEpochMillis(readLong(entries, "updatedAtEpochMillis"));
        state.setUpdatedTurnNo(readLong(entries, "updatedTurnNo"));
        return state;
    }

    private Map<String, Object> buildDelta(ShoppingPreferenceState before, ShoppingPreferenceState state) {
        Map<String, Object> delta = new LinkedHashMap<>();
        // 变更日志只记录可参与推荐的偏好槽位，不重复记录来源、置信度等元数据。
        for (String field : PREFERENCE_FIELDS) {
            Object oldValue = readField(before, field);
            Object newValue = readField(state, field);
            if (!Objects.equals(oldValue, newValue)) {
                delta.put(field, newValue);
            }
        }
        return delta;
    }

    private Map<String, String> hashUpdates(Map<String, Object> delta, ShoppingPreferenceState state) {
        Map<String, String> updates = new LinkedHashMap<>();
        delta.forEach((field, value) -> {
            if (value != null) {
                updates.put(field, value.toString());
            }
        });
        // 只有实际偏好变化时才同步刷新本次状态的审计元数据。
        putIfPresent(updates, "source", state.getSource());
        putIfPresent(updates, "confidence", state.getConfidence());
        putIfPresent(updates, "updatedAtEpochMillis", state.getUpdatedAtEpochMillis());
        putIfPresent(updates, "updatedTurnNo", state.getUpdatedTurnNo());
        return updates;
    }

    private List<String> clearedFields(Map<String, Object> delta) {
        List<String> clearedFields = new ArrayList<>();
        delta.forEach((field, value) -> {
            if (value == null) {
                clearedFields.add(field);
            }
        });
        return clearedFields;
    }

    private ShoppingPreferenceState copyOf(ShoppingPreferenceState source) {
        ShoppingPreferenceState copy = new ShoppingPreferenceState();
        if (source == null) {
            return copy;
        }
        copy.setCategory(source.getCategory());
        copy.setBudget(source.getBudget());
        copy.setBrand(source.getBrand());
        copy.setSize(source.getSize());
        copy.setColor(source.getColor());
        copy.setStyle(source.getStyle());
        copy.setUsageScenario(source.getUsageScenario());
        copy.setSource(source.getSource());
        copy.setConfidence(source.getConfidence());
        copy.setUpdatedAtEpochMillis(source.getUpdatedAtEpochMillis());
        copy.setUpdatedTurnNo(source.getUpdatedTurnNo());
        return copy;
    }

    private Object readField(ShoppingPreferenceState state, String field) {
        if (state == null) {
            return null;
        }
        return switch (field) {
            case "category" -> state.getCategory();
            case "budget" -> state.getBudget();
            case "brand" -> state.getBrand();
            case "size" -> state.getSize();
            case "color" -> state.getColor();
            case "style" -> state.getStyle();
            case "usageScenario" -> state.getUsageScenario();
            default -> null;
        };
    }

    private void putIfPresent(Map<String, String> updates, String field, Object value) {
        if (value != null) {
            updates.put(field, value.toString());
        }
    }

    private String readString(Map<Object, Object> entries, String field) {
        Object value = entries.get(field);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private Integer readInteger(Map<Object, Object> entries, String field) {
        String text = readString(entries, field);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException ex) {
            log.warn("Failed to parse shopping preference integer field. field={}, value={}", field, text);
            return null;
        }
    }

    private Integer readBudget(Map<Object, Object> entries) {
        Integer budget = readInteger(entries, "budget");
        if (budget != null) {
            return budget;
        }
        Integer legacyMax = readInteger(entries, "budgetMax");
        if (legacyMax != null) {
            return legacyMax;
        }
        return readInteger(entries, "budgetMin");
    }

    private Long readLong(Map<Object, Object> entries, String field) {
        String text = readString(entries, field);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.valueOf(text);
        }
        catch (NumberFormatException ex) {
            log.warn("Failed to parse shopping preference long field. field={}, value={}", field, text);
            return null;
        }
    }

    private Double readDouble(Map<Object, Object> entries, String field) {
        String text = readString(entries, field);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Double.valueOf(text);
        }
        catch (NumberFormatException ex) {
            log.warn("Failed to parse shopping preference double field. field={}, value={}", field, text);
            return null;
        }
    }

    private String stateKey(String userId, String sessionId) {
        return STATE_PREFIX + normalize(userId) + ":" + normalize(sessionId);
    }

    private String changesKey(String userId, String sessionId) {
        return CHANGES_PREFIX + normalize(userId) + ":" + normalize(sessionId);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "default";
    }

    /**
     * 单轮偏好补丁。非空槽位表示更新，clearFields 表示显式删除已有槽位。
     */
    public record ShoppingPreferencePatch(
            @Size(max = 64)
            String category,
            @PositiveOrZero
            Integer budget,
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
                                       Integer budget,
                                       String brand,
                                       String size,
                                       String color,
                                       String style,
                                       String usageScenario) {
            this(
                    category,
                    budget,
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
