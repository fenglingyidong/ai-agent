package com.example.ragagent.mall;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MallAuthCache {

    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();

    public void put(String cacheKey, String token) {
        if (!StringUtils.hasText(cacheKey) || !StringUtils.hasText(token)) {
            return;
        }
        tokenCache.put(cacheKey.trim(), token.trim());
    }

    public String get(String cacheKey) {
        if (!StringUtils.hasText(cacheKey)) {
            return "";
        }
        return tokenCache.getOrDefault(cacheKey.trim(), "");
    }

    public void clear(String cacheKey) {
        if (StringUtils.hasText(cacheKey)) {
            tokenCache.remove(cacheKey.trim());
        }
    }

    public void clearByPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return;
        }
        String normalizedPrefix = prefix.trim();
        tokenCache.keySet().removeIf(key -> key.startsWith(normalizedPrefix));
    }
}
