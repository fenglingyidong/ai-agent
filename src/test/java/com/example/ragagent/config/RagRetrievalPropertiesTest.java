package com.example.ragagent.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultPropertiesShouldBeValid() {
        RagRetrievalProperties properties = new RagRetrievalProperties();

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectChildResultWindowWhenMaximumIsSmallerThanMinimum() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setMinChildResultsToKeep(4);
        properties.setMaxChildResultsToConsider(2);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectBm25FutureTimeoutWhenSmallerThanRpcDeadline() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setBm25RpcDeadlineMs(2_000);
        properties.setBm25FutureTimeoutMs(1_000);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectParallelExecutorWhenMaximumIsSmallerThanCoreSize() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setParallelExecutorCoreSize(8);
        properties.setParallelExecutorMaxSize(4);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
