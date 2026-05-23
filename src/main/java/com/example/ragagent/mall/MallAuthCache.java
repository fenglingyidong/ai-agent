package com.example.ragagent.mall;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
public class MallAuthCache {

    private static final String KEY_PREFIX = "mall:auth:";

    private final StringRedisTemplate redisTemplate;
    private final MallProperties properties;

    public MallAuthCache(StringRedisTemplate redisTemplate, MallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void put(String cacheKey, String token) {
        if (!StringUtils.hasText(cacheKey) || !StringUtils.hasText(token)) {
            return;
        }
        redisTemplate.opsForValue().set(redisKey(cacheKey), token.trim(), authCacheTtl());
    }

    public String get(String cacheKey) {
        if (!StringUtils.hasText(cacheKey)) {
            return "";
        }
        String token = redisTemplate.opsForValue().get(redisKey(cacheKey));
        return StringUtils.hasText(token) ? token.trim() : "";
    }

    public void clear(String cacheKey) {
        if (StringUtils.hasText(cacheKey)) {
            redisTemplate.delete(redisKey(cacheKey));
        }
    }

    private String redisKey(String cacheKey) {
        return KEY_PREFIX + cacheKey.trim();
    }

    private Duration authCacheTtl() {
        Duration ttl = properties == null ? null : properties.getAuthCacheTtl();
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(2) : ttl;
    }
}
