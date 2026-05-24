package com.example.ragagent.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingStateServiceTest {

    @Test
    void mergePreferenceShouldTrimPartialPatchAndUseConfiguredTtl() {
        TestFixture fixture = fixture(Duration.ofDays(3));
        when(fixture.valueOperations.get("shopping:preference:alice:session-1")).thenReturn("");

        ShoppingPreferenceState state = fixture.service.mergePreference(
                " alice ",
                " session-1 ",
                new ShoppingStateService.ShoppingPreferencePatch(
                        " 跑鞋 ",
                        100,
                        300,
                        " Stride ",
                        null,
                        "",
                        " 通勤 ",
                        null
                )
        );

        assertEquals("跑鞋", state.getCategory());
        assertEquals(100, state.getBudgetMin());
        assertEquals(300, state.getBudgetMax());
        assertEquals("Stride", state.getBrand());
        assertEquals("通勤", state.getStyle());
        assertNull(state.getColor());
        verify(fixture.valueOperations).set(
                eq("shopping:preference:alice:session-1"),
                anyString(),
                eq(Duration.ofDays(3))
        );
    }

    @Test
    void mergePreferenceShouldRejectInvalidBudgetRange() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.valueOperations.get("shopping:preference:alice:session-1")).thenReturn("");

        assertThrows(IllegalArgumentException.class, () -> fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch(null, 500, 100, null, null, null, null, null)
        ));

        verify(fixture.valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void mergePreferenceShouldClearBrandWhenUserSaysBrandIsUnlimited() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.valueOperations.get("shopping:preference:alice:session-1"))
                .thenReturn("""
                        {"category":"跑鞋","budgetMax":500,"brand":"Nike","size":"42","color":"黑色","style":"缓震","usageScenario":"通勤"}
                        """);

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
        assertEquals(3L, state.getUpdatedTurnNo());
        assertEquals(ShoppingPreferenceSource.USER_EXPLICIT.name(), state.getSource());
    }

    @Test
    void mergePreferenceShouldClearCategorySpecificFieldsWhenCategoryChanges() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.valueOperations.get("shopping:preference:alice:session-1"))
                .thenReturn("""
                        {"category":"跑鞋","budgetMax":500,"brand":"Nike","size":"42","color":"黑色","style":"缓震","usageScenario":"通勤"}
                        """);

        ShoppingPreferenceState state = fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch(
                        "儿童积木",
                        null,
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
        assertEquals(300, state.getBudgetMax());
        assertNull(state.getBrand());
        assertNull(state.getSize());
        assertNull(state.getColor());
        assertNull(state.getStyle());
        assertEquals("5岁生日礼物", state.getUsageScenario());
    }

    @Test
    void mergePreferenceShouldClearStaleFieldsWhenCategoryIsAddedToUncategorizedState() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.valueOperations.get("shopping:preference:alice:session-1"))
                .thenReturn("""
                        {"budgetMax":500,"brand":"Nike","size":"42","color":"黑色","style":"缓震","usageScenario":"通勤"}
                        """);

        ShoppingPreferenceState state = fixture.service.mergePreference(
                "alice",
                "session-1",
                new ShoppingStateService.ShoppingPreferencePatch(
                        "儿童积木",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "5岁生日礼物",
                        Set.of(),
                        ShoppingPreferenceSource.USER_EXPLICIT.name(),
                        1.0,
                        5L
                )
        );

        assertEquals("儿童积木", state.getCategory());
        assertEquals(500, state.getBudgetMax());
        assertNull(state.getBrand());
        assertNull(state.getSize());
        assertNull(state.getColor());
        assertNull(state.getStyle());
        assertEquals("5岁生日礼物", state.getUsageScenario());
    }

    @Test
    void loadPreferenceShouldReturnEmptyStateWhenStoredJsonIsBroken() {
        TestFixture fixture = fixture(Duration.ofDays(7));
        when(fixture.valueOperations.get("shopping:preference:alice:session-1")).thenReturn("{bad-json");

        ShoppingPreferenceState state = fixture.service.loadPreference("alice", "session-1");

        assertNull(state.getCategory());
    }

    @SuppressWarnings("unchecked")
    private TestFixture fixture(Duration preferenceTtl) {
        ShoppingStateService service = new ShoppingStateService();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "preferenceTtl", preferenceTtl);
        ReflectionTestUtils.setField(service, "mergePolicy", new ShoppingPreferenceMergePolicy());
        return new TestFixture(service, valueOperations);
    }

    private record TestFixture(ShoppingStateService service, ValueOperations<String, String> valueOperations) {
    }
}
