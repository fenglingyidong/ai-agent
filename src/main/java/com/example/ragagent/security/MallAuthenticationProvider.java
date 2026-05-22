package com.example.ragagent.security;

import com.example.ragagent.mall.MallApiClient;
import com.example.ragagent.mall.MallAuthCache;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.StringUtils;

import java.util.List;

public class MallAuthenticationProvider implements AuthenticationProvider {

    private final MallApiClient mallApiClient;
    private final MallAuthCache mallAuthCache;

    public MallAuthenticationProvider(MallApiClient mallApiClient, MallAuthCache mallAuthCache) {
        this.mallApiClient = mallApiClient;
        this.mallAuthCache = mallAuthCache;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication == null ? "" : String.valueOf(authentication.getPrincipal());
        String password = authentication == null || authentication.getCredentials() == null
                ? ""
                : String.valueOf(authentication.getCredentials());
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BadCredentialsException("Username and password are required");
        }

        MallApiClient.LoginResponse loginResponse = mallApiClient.login(username, password)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        mallAuthCache.put(buildCacheKey(loginResponse.username()), "Bearer " + loginResponse.token().trim());

        return UsernamePasswordAuthenticationToken.authenticated(
                loginResponse.username(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private String buildCacheKey(String username) {
        return (StringUtils.hasText(username) ? username.trim() : "anonymous") + ":default:mall";
    }
}
