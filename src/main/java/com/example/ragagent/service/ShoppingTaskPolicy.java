package com.example.ragagent.service;

import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 描述某类购物任务允许的工具、必需槽位和附加提示词约束。
 */
record ShoppingTaskPolicy(String id,
                          String name,
                          Set<String> intents,
                          Set<String> requiredSlots,
                          Set<String> allowedToolNames,
                          boolean confirmationRequired,
                          String promptFragment) {

    ShoppingTaskPolicy {
        id = normalizeRequired(id, "policy id");
        name = normalizeRequired(name, "policy name");
        intents = immutableTextSet(intents);
        requiredSlots = immutableTextSet(requiredSlots);
        allowedToolNames = immutableTextSet(allowedToolNames);
        promptFragment = promptFragment == null ? "" : promptFragment.trim();
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static Set<String> immutableTextSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> target = new LinkedHashSet<>();
        for (String value : source) {
            if (StringUtils.hasText(value)) {
                target.add(value.trim());
            }
        }
        return target.isEmpty() ? Set.of() : Collections.unmodifiableSet(target);
    }
}
