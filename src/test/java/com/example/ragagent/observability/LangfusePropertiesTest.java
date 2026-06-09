package com.example.ragagent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangfusePropertiesTest {

    @Test
    void bindShouldDefaultToDisabledPromptCapture() {
        LangfuseProperties properties = bind(Map.of(
                "app.observability.langfuse.enabled", "true",
                "app.observability.langfuse.base-url", "http://localhost:3000"
        ));

        assertTrue(properties.isEnabled());
        assertFalse(properties.isCapturePrompt());
    }

    @Test
    void bindShouldDefaultBaseUrlToLocalLangfusePort() {
        LangfuseProperties properties = bind(Map.of());

        assertEquals("http://localhost:3001", properties.getBaseUrl());
    }

    private LangfuseProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bind("app.observability.langfuse", Bindable.of(LangfuseProperties.class))
                .orElseGet(LangfuseProperties::new);
    }
}
