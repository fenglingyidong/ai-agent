package com.example.ragagent.security;

import com.example.ragagent.mall.MallApiClient;
import com.example.ragagent.mall.MallAuthCache;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MallAuthenticationProviderTest {

    @Test
    void authenticateShouldDelegateToMallLoginAndCacheToken() {
        MallApiClient mallApiClient = mock(MallApiClient.class);
        MallAuthCache mallAuthCache = new MallAuthCache();
        when(mallApiClient.login("alice", "demo123")).thenReturn(Optional.of(
                new MallApiClient.LoginResponse("10001", "alice", "mall-token")
        ));
        MallAuthenticationProvider provider = new MallAuthenticationProvider(mallApiClient, mallAuthCache);

        var authentication = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "demo123")
        );

        assertEquals("alice", authentication.getName());
        assertTrue(authentication.isAuthenticated());
        assertEquals("Bearer mall-token", mallAuthCache.get("alice:default:mall"));
    }

    @Test
    void authenticateShouldRejectInvalidMallPassword() {
        MallApiClient mallApiClient = mock(MallApiClient.class);
        when(mallApiClient.login("alice", "wrong")).thenReturn(Optional.empty());
        MallAuthenticationProvider provider = new MallAuthenticationProvider(mallApiClient, new MallAuthCache());

        assertThrows(BadCredentialsException.class, () -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "wrong")
        ));
    }
}
