package com.example.ragagent.security;

import com.example.ragagent.mall.MallApiClient;
import com.example.ragagent.mall.MallAuthCache;
import com.example.ragagent.mall.MallProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallAuthenticationProviderTest {

    @Test
    void authenticateShouldDelegateToMallLoginAndCacheToken() {
        MallApiClient mallApiClient = mock(MallApiClient.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        MallProperties properties = new MallProperties();
        MallAuthCache mallAuthCache = new MallAuthCache(redisTemplate, properties);
        when(mallApiClient.login("alice", "demo123")).thenReturn(Optional.of(
                new MallApiClient.LoginResponse("10001", "alice", "mall-token")
        ));
        when(valueOperations.get("mall:auth:alice:default:mall")).thenReturn("Bearer mall-token");
        MallAuthenticationProvider provider = new MallAuthenticationProvider(mallApiClient, mallAuthCache);

        var authentication = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "demo123")
        );

        assertEquals("alice", authentication.getName());
        assertTrue(authentication.isAuthenticated());
        verify(valueOperations).set(
                eq("mall:auth:alice:default:mall"),
                eq("Bearer mall-token"),
                eq(Duration.ofHours(2))
        );
        assertEquals("Bearer mall-token", mallAuthCache.get("alice:default:mall"));
    }

    @Test
    void authenticateShouldRejectInvalidMallPassword() {
        MallApiClient mallApiClient = mock(MallApiClient.class);
        when(mallApiClient.login("alice", "wrong")).thenReturn(Optional.empty());
        MallAuthenticationProvider provider = new MallAuthenticationProvider(mallApiClient, mock(MallAuthCache.class));

        assertThrows(BadCredentialsException.class, () -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "wrong")
        ));
    }
}
