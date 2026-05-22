package com.example.ragagent.service;

import java.util.Map;

final class StreamingSensitiveValueRestorer {

    private final Map<String, String> sensitiveValues;
    private final StringBuilder pending = new StringBuilder();

    StreamingSensitiveValueRestorer(Map<String, String> sensitiveValues) {
        this.sensitiveValues = sensitiveValues == null ? Map.of() : sensitiveValues;
    }

    String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }

        pending.append(chunk);
        int keepFrom = findIncompletePlaceholderStart(pending);
        String readyText = pending.substring(0, keepFrom);
        String remaining = pending.substring(keepFrom);
        pending.setLength(0);
        pending.append(remaining);
        return restore(readyText);
    }

    String flush() {
        String all = pending.toString();
        pending.setLength(0);
        return restore(all);
    }

    private String restore(String text) {
        String restored = text;
        for (Map.Entry<String, String> entry : sensitiveValues.entrySet()) {
            restored = restored.replace(entry.getKey(), entry.getValue());
        }
        return restored;
    }

    private int findIncompletePlaceholderStart(CharSequence text) {
        for (int i = text.length() - 2; i >= 0; i--) {
            if (text.charAt(i) == '[' && text.charAt(i + 1) == '[') {
                if (!containsPlaceholderEnd(text, i + 2)) {
                    return i;
                }
                break;
            }
        }
        return text.length();
    }

    private boolean containsPlaceholderEnd(CharSequence text, int start) {
        for (int i = start; i < text.length() - 1; i++) {
            if (text.charAt(i) == ']' && text.charAt(i + 1) == ']') {
                return true;
            }
        }
        return false;
    }
}
