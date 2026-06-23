package com.example.ragagent.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingStateServiceTest {

    private static final String STATE_KEY = "shopping:preference:v2:state:alice:session-1";
    private static final String CHANGES_KEY = "shopping:preference:v2:changes:alice:session-1";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Test
    void loadPreferenceShouldReadStateFromHash() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of(
                "category", "跑鞋",
                "budget", "300",
                "brand", "Stride",
                "confidence", "0.92",
                "updatedTurnNo", "6"
        ));

        ShoppingPreferenceState state = fixture.service.loadPreference("alice", "session-1");

        assertEquals("跑鞋", state.getCategory());
        assertEquals(300, state.getBudget());
        assertEquals("Stride", state.getBrand());
        assertEquals(0.92, state.getConfidence());
        assertEquals(6L, state.getUpdatedTurnNo());
    }

    @Test
    void mergePreferenceShouldOverrideBudgetAsSingleField() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of(
                "category", "猫粮,猫砂",
                "budget", "450"
        ));

        ShoppingPreferenceState state = fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch(
                        null,
                        400,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        assertEquals(400, state.getBudget());
    }

    @Test
    void loadPreferenceShouldReturnEmptyStateWhenHashIsEmpty() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of());

        ShoppingPreferenceState state = fixture.service.loadPreference("alice", "session-1");

        assertNotNull(state);
        assertNull(state.getCategory());
    }

    @Test
    void loadPreferenceSnapshotShouldReadStateAndRecentChanges() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of(
                "brand", "OPPO",
                "budget", "500"
        ));
        when(fixture.listOperations.range(CHANGES_KEY, 0, -1)).thenReturn(List.of(
                "{\"brand\":\"华为\"}",
                "{\"brand\":\"OPPO\"}",
                "{\"budget\":500}"
        ));

        ShoppingPreferenceSnapshot snapshot = fixture.service.loadPreferenceSnapshot("alice", "session-1");

        assertEquals("OPPO", snapshot.state().getBrand());
        assertEquals(500, snapshot.state().getBudget());
        assertEquals(3, snapshot.recentChanges().size());
        assertEquals("华为", snapshot.recentChanges().get(0).get("brand"));
        assertEquals("OPPO", snapshot.recentChanges().get(1).get("brand"));
        assertEquals(500, snapshot.recentChanges().get(2).get("budget"));
    }

    @Test
    void loadPreferenceSnapshotShouldIgnoreNullAndEmptyDeltaEntries() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of());
        when(fixture.listOperations.range(CHANGES_KEY, 0, -1)).thenReturn(List.of(
                "null",
                "{}",
                "{\"brand\":\"OPPO\"}"
        ));

        ShoppingPreferenceSnapshot snapshot = fixture.service.loadPreferenceSnapshot("alice", "session-1");

        assertEquals(1, snapshot.recentChanges().size());
        assertEquals("OPPO", snapshot.recentChanges().get(0).get("brand"));
    }

    @Test
    void mergePreferenceShouldWriteChangedHashFieldsDeltaLogAndConfiguredTtl() throws Exception {
        TestFixture fixture = fixture(Duration.ofDays(3));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of());
        ArgumentCaptor<Map<String, String>> hashCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> deltaCaptor = ArgumentCaptor.forClass(String.class);

        ShoppingPreferenceState state = fixture.service.mergePreference(
                " alice ",
                " session-1 ",
                new ShoppingStateService.ShoppingPreferencePatch(
                        " 跑鞋 ",
                        300,
                        " Stride ",
                        null,
                        "",
                        " 通勤 ",
                        null
                )
        );

        assertEquals("跑鞋", state.getCategory());
        assertEquals(300, state.getBudget());
        assertEquals("Stride", state.getBrand());
        assertEquals("通勤", state.getStyle());
        assertNull(state.getColor());

        verify(fixture.hashOperations).putAll(eq(STATE_KEY), hashCaptor.capture());
        Map<String, String> hashFields = hashCaptor.getValue();
        assertEquals("跑鞋", hashFields.get("category"));
        assertEquals("300", hashFields.get("budget"));
        assertEquals("Stride", hashFields.get("brand"));
        assertEquals("通勤", hashFields.get("style"));
        assertEquals(ShoppingPreferenceSource.USER_EXPLICIT.name(), hashFields.get("source"));

        verify(fixture.listOperations).rightPush(eq(CHANGES_KEY), deltaCaptor.capture());
        Map<String, Object> delta = fixture.objectMapper.readValue(deltaCaptor.getValue(), MAP_TYPE);
        assertEquals("跑鞋", delta.get("category"));
        assertEquals(300, delta.get("budget"));
        assertEquals("Stride", delta.get("brand"));
        assertEquals("通勤", delta.get("style"));

        verify(fixture.listOperations).trim(CHANGES_KEY, -5, -1);
        verify(fixture.redisTemplate).expire(STATE_KEY, Duration.ofDays(3));
        verify(fixture.redisTemplate).expire(CHANGES_KEY, Duration.ofDays(3));
    }

    @Test
    void mergePreferenceShouldClearHashFieldsAndRecordNullDelta() throws Exception {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of(
                "category", "跑鞋",
                "budget", "500",
                "brand", "Nike",
                "size", "42",
                "color", "黑色",
                "style", "缓震",
                "usageScenario", "通勤"
        ));
        ArgumentCaptor<String> deltaCaptor = ArgumentCaptor.forClass(String.class);

        ShoppingPreferenceState state = fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch(
                        "儿童积木",
                        300,
                        null,
                        null,
                        null,
                        null,
                        "5岁生日礼物",
                        Set.of(),
                        ShoppingPreferenceSource.USER_EXPLICIT.name(),
                        1.0,
                        4L
                )
        );

        assertEquals("儿童积木", state.getCategory());
        assertEquals(300, state.getBudget());
        assertNull(state.getBrand());
        assertNull(state.getSize());
        assertNull(state.getColor());
        assertNull(state.getStyle());
        assertEquals("5岁生日礼物", state.getUsageScenario());

        verify(fixture.hashOperations).delete(STATE_KEY, "brand", "size", "color", "style");
        verify(fixture.listOperations).rightPush(eq(CHANGES_KEY), deltaCaptor.capture());
        Map<String, Object> delta = fixture.objectMapper.readValue(deltaCaptor.getValue(), MAP_TYPE);
        assertEquals("儿童积木", delta.get("category"));
        assertEquals(300, delta.get("budget"));
        assertNull(delta.get("brand"));
        assertNull(delta.get("size"));
        assertNull(delta.get("color"));
        assertNull(delta.get("style"));
        assertEquals("5岁生日礼物", delta.get("usageScenario"));
    }

    @Test
    void mergePreferenceShouldNotWriteRedisWhenDeltaSerializationFails() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any(Map.class))).thenThrow(new JsonProcessingException("boom") {
        });
        TestFixture fixture = fixture(Duration.ofDays(7), objectMapper);
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of());

        assertThrows(IllegalStateException.class, () -> fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch("跑鞋", null, null, null, null, null, null)
        ));

        verify(fixture.hashOperations, never()).putAll(anyString(), any());
        verify(fixture.hashOperations, never()).delete(eq(STATE_KEY), any());
        verify(fixture.listOperations, never()).rightPush(anyString(), anyString());
        verify(fixture.redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void mergePreferenceShouldSkipRedisWritesWhenPreferenceFieldsDoNotChange() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of(
                "category", "跑鞋",
                "budget", "500",
                "brand", "Nike"
        ));

        ShoppingPreferenceState state = fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch(
                        "跑鞋",
                        500,
                        "Nike",
                        null,
                        null,
                        null,
                        null
                )
        );

        assertEquals("跑鞋", state.getCategory());
        assertEquals(500, state.getBudget());
        assertEquals("Nike", state.getBrand());
        verify(fixture.hashOperations, never()).putAll(anyString(), any());
        verify(fixture.hashOperations, never()).delete(eq(STATE_KEY), any());
        verify(fixture.listOperations, never()).rightPush(anyString(), anyString());
        verify(fixture.listOperations, never()).trim(anyString(), any(Long.class), any(Long.class));
        verify(fixture.redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void mergePreferenceShouldClearExplicitFieldWithoutChangingOtherSlots() throws Exception {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.hashOperations.entries(STATE_KEY)).thenReturn(Map.of(
                "category", "跑鞋",
                "budget", "500",
                "brand", "Nike",
                "size", "42"
        ));
        ArgumentCaptor<String> deltaCaptor = ArgumentCaptor.forClass(String.class);

        ShoppingPreferenceState state = fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of("brand"),
                        ShoppingPreferenceSource.USER_EXPLICIT.name(),
                        1.0,
                        3L
                )
        );

        assertEquals("跑鞋", state.getCategory());
        assertNull(state.getBrand());
        assertEquals("42", state.getSize());

        verify(fixture.hashOperations).delete(STATE_KEY, "brand");
        verify(fixture.listOperations).rightPush(eq(CHANGES_KEY), deltaCaptor.capture());
        Map<String, Object> delta = fixture.objectMapper.readValue(deltaCaptor.getValue(), MAP_TYPE);
        assertNull(delta.get("brand"));
        assertEquals(1, delta.size());
    }

    @Test
    void mergePreferenceShouldBeSynchronizedToProtectSingleInstanceReadModifyWrite() throws Exception {
        Method method = ShoppingStateService.class.getDeclaredMethod(
                "mergePreference",
                String.class,
                String.class,
                ShoppingStateService.ShoppingPreferencePatch.class
        );

        assertTrue(Modifier.isSynchronized(method.getModifiers()));
    }

    @Test
    void shoppingPreferencePatchShouldClampNonFiniteConfidence() {
        ShoppingStateService.ShoppingPreferencePatch nanPatch = new ShoppingStateService.ShoppingPreferencePatch(
                null, null, null, null, null, null, null,
                Set.of(), ShoppingPreferenceSource.USER_EXPLICIT.name(), Double.NaN, null
        );
        ShoppingStateService.ShoppingPreferencePatch infinityPatch = new ShoppingStateService.ShoppingPreferencePatch(
                null, null, null, null, null, null, null,
                Set.of(), ShoppingPreferenceSource.USER_EXPLICIT.name(), Double.POSITIVE_INFINITY, null
        );
        ShoppingStateService.ShoppingPreferencePatch negativePatch = new ShoppingStateService.ShoppingPreferencePatch(
                null, null, null, null, null, null, null,
                Set.of(), ShoppingPreferenceSource.USER_EXPLICIT.name(), -1.0, null
        );
        ShoppingStateService.ShoppingPreferencePatch tooLargePatch = new ShoppingStateService.ShoppingPreferencePatch(
                null, null, null, null, null, null, null,
                Set.of(), ShoppingPreferenceSource.USER_EXPLICIT.name(), 2.0, null
        );

        assertFalse(Double.isNaN(nanPatch.confidence()));
        assertEquals(1.0, nanPatch.confidence());
        assertTrue(Double.isFinite(infinityPatch.confidence()));
        assertEquals(1.0, infinityPatch.confidence());
        assertEquals(0.0, negativePatch.confidence());
        assertEquals(1.0, tooLargePatch.confidence());
    }

    @SuppressWarnings("unchecked")
    private TestFixture fixture(Duration preferenceTtl) {
        return fixture(preferenceTtl, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private TestFixture fixture(Duration preferenceTtl, ObjectMapper objectMapper) {
        ShoppingStateService service = new ShoppingStateService();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "preferenceTtl", preferenceTtl);
        ReflectionTestUtils.setField(service, "mergePolicy", new ShoppingPreferenceMergePolicy());
        return new TestFixture(service, redisTemplate, hashOperations, listOperations, objectMapper);
    }

    private record TestFixture(
            ShoppingStateService service,
            StringRedisTemplate redisTemplate,
            HashOperations<String, Object, Object> hashOperations,
            ListOperations<String, String> listOperations,
            ObjectMapper objectMapper
    ) {
    }
}
