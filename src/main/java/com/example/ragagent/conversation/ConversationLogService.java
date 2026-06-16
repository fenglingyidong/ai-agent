package com.example.ragagent.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ragagent.conversation.entity.ConversationSessionEntity;
import com.example.ragagent.conversation.entity.ConversationTurnEntity;
import com.example.ragagent.conversation.mapper.ConversationSessionMapper;
import com.example.ragagent.conversation.mapper.ConversationSessionSummaryRow;
import com.example.ragagent.conversation.mapper.ConversationTurnMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationLogService {

    public static final String DEFAULT_USER_ID = "anonymous";
    public static final String DEFAULT_SESSION_ID = "default";
    private static final String DEFAULT_SESSION_TITLE = "新会话";
    private static final String IMAGE_SESSION_TITLE = "图片导购咨询";
    private static final int MAX_SESSION_TITLE_LENGTH = 40;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ConversationSessionMapper sessionMapper;
    private final ConversationTurnMapper turnMapper;
    private final ObjectMapper objectMapper;
    private final ConversationLogProperties properties;

    public ConversationLogService(ConversationSessionMapper sessionMapper,
                                  ConversationTurnMapper turnMapper,
                                  ObjectMapper objectMapper,
                                  ConversationLogProperties properties) {
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public ConversationTurnRecord beginTurn(String userId,
                                            String sessionId,
                                            String modelId,
                                            boolean webSearchEnabled,
                                            String userText,
                                            int mediaCount) {
        if (!properties.isEnabled()) {
            return null;
        }
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        String normalizedSessionId = normalize(sessionId, DEFAULT_SESSION_ID);
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.upsertSession(
                normalizedUserId,
                normalizedSessionId,
                buildSessionTitle(userText, mediaCount),
                now
        );
        Long nextTurnNo = sessionMapper.lockNextTurnNo(normalizedUserId, normalizedSessionId);
        long turnNo = nextTurnNo == null || nextTurnNo < 1 ? 1L : nextTurnNo;
        sessionMapper.updateNextTurnNo(normalizedUserId, normalizedSessionId, turnNo + 1, now);

        ConversationTurnRecord record = ConversationTurnRecord.started(
                UUID.randomUUID().toString(),
                normalizedUserId,
                normalizedSessionId,
                turnNo,
                modelId,
                webSearchEnabled,
                userText,
                mediaCount,
                toEpochMillis(now)
        );
        turnMapper.insert(toEntity(record));
        turnMapper.deleteOverflowTurns(normalizedUserId, normalizedSessionId, properties.getMaxTurnsPerSession());
        return record;
    }

    public void completeTurn(ConversationTurnRecord turn, String assistantText) {
        saveResult(turn, ConversationTurnStatus.COMPLETED, nullToEmpty(assistantText), null);
    }

    public void partialTurn(ConversationTurnRecord turn, String assistantText, String reason) {
        saveResult(turn, ConversationTurnStatus.PARTIAL, nullToEmpty(assistantText), normalizeError(reason));
    }

    public void failTurn(ConversationTurnRecord turn, String assistantText, Throwable throwable) {
        saveResult(turn, ConversationTurnStatus.FAILED, nullToEmpty(assistantText), normalizeFailureError(throwable));
    }

    public List<ConversationTurnRecord> listRecentTurns(String userId, String sessionId, int limit) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        String normalizedSessionId = normalize(sessionId, DEFAULT_SESSION_ID);
        int clampedLimit = Math.max(1, Math.min(limit, properties.getMaxTurnsPerSession()));
        return turnMapper.selectRecentTurns(normalizedUserId, normalizedSessionId, clampedLimit)
                .stream()
                .map(this::toRecord)
                .toList();
    }

    public List<ConversationSessionSummary> listRecentSessions(String userId, int limit) {
        return listRecentSessions(userId, limit, 0);
    }

    public List<ConversationSessionSummary> listRecentSessions(String userId, int limit, int offset) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        return sessionMapper.selectRecentSessionSummaries(normalizedUserId, safeLimit, safeOffset)
                .stream()
                .map(this::toSessionSummary)
                .toList();
    }

    private String buildSessionTitle(String userText, int mediaCount) {
        String normalized = userText == null ? "" : userText.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return mediaCount > 0 ? IMAGE_SESSION_TITLE : DEFAULT_SESSION_TITLE;
        }
        return normalized.length() <= MAX_SESSION_TITLE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SESSION_TITLE_LENGTH);
    }

    @Transactional
    public void deleteSession(String userId, String sessionId) {
        if (!properties.isEnabled()) {
            return;
        }
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        String normalizedSessionId = normalize(sessionId, DEFAULT_SESSION_ID);
        turnMapper.deleteByUserAndSession(normalizedUserId, normalizedSessionId);
        LambdaQueryWrapper<ConversationSessionEntity> wrapper = new LambdaQueryWrapper<ConversationSessionEntity>()
                .eq(ConversationSessionEntity::getUserId, normalizedUserId)
                .eq(ConversationSessionEntity::getSessionId, normalizedSessionId);
        sessionMapper.delete(wrapper);
    }

    private void saveResult(ConversationTurnRecord turn,
                            ConversationTurnStatus status,
                            String assistantText,
                            String errorMessage) {
        if (!properties.isEnabled() || turn == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ConversationTurnRecord result = switch (status) {
            case COMPLETED -> turn.complete(assistantText, toEpochMillis(now));
            case PARTIAL -> turn.partial(assistantText, errorMessage, toEpochMillis(now));
            case FAILED -> turn.fail(assistantText, errorMessage, toEpochMillis(now));
            case PROCESSING -> turn;
        };
        turnMapper.updateTurnResult(
                result.id(),
                result.assistantText(),
                result.status().name(),
                emptyToNull(result.errorMessage()),
                now,
                serializeMetadata(result.metadata()),
                now
        );
    }

    private ConversationTurnEntity toEntity(ConversationTurnRecord record) {
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setTurnId(record.id());
        entity.setUserId(record.userId());
        entity.setSessionId(record.sessionId());
        entity.setTurnNo(record.turnNo());
        entity.setModelId(record.modelId());
        entity.setWebSearchEnabled(record.webSearchEnabled());
        entity.setUserText(record.userText());
        entity.setAssistantText(record.assistantText());
        entity.setStatus(record.status().name());
        entity.setErrorMessage(emptyToNull(record.errorMessage()));
        entity.setMetadataJson(serializeMetadata(record.metadata()));
        entity.setCreatedAt(toLocalDateTime(record.createdAtEpochMillis()));
        entity.setCompletedAt(toLocalDateTime(record.completedAtEpochMillis()));
        entity.setUpdatedAt(toLocalDateTime(record.createdAtEpochMillis()));
        return entity;
    }

    private ConversationTurnRecord toRecord(ConversationTurnEntity entity) {
        return new ConversationTurnRecord(
                nullToEmpty(entity.getTurnId()),
                nullToEmpty(entity.getUserId()),
                nullToEmpty(entity.getSessionId()),
                entity.getTurnNo() == null ? 0L : entity.getTurnNo(),
                nullToEmpty(entity.getModelId()),
                Boolean.TRUE.equals(entity.getWebSearchEnabled()),
                nullToEmpty(entity.getUserText()),
                nullToEmpty(entity.getAssistantText()),
                ConversationTurnStatus.valueOf(entity.getStatus()),
                nullToEmpty(entity.getErrorMessage()),
                toEpochMillis(entity.getCreatedAt()),
                toEpochMillis(entity.getCompletedAt()),
                deserializeMetadata(entity.getMetadataJson())
        );
    }

    private ConversationSessionSummary toSessionSummary(ConversationSessionSummaryRow row) {
        return new ConversationSessionSummary(
                nullToEmpty(row.getSessionId()),
                nullToEmpty(row.getTitle()),
                toEpochMillis(row.getCreatedAt()),
                toEpochMillis(row.getUpdatedAt()),
                row.getTurnCount() == null ? 0L : row.getTurnCount(),
                nullToEmpty(row.getLatestUserText()),
                nullToEmpty(row.getLatestAssistantText())
        );
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Collections.emptyMap() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize conversation turn metadata.", ex);
        }
    }

    private Map<String, Object> deserializeMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize conversation turn metadata.", ex);
        }
    }

    private String normalizeError(Throwable throwable) {
        return throwable == null ? "unknown error" : normalizeFailureError(throwable);
    }

    private String normalizeError(String message) {
        return message == null || message.isBlank() ? "partial response" : message;
    }

    private String normalizeFailureError(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        if (epochMillis <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private long toEpochMillis(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0L;
        }
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
