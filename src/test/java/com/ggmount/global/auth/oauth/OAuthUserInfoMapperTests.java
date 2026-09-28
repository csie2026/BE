package com.ggmount.global.auth.oauth;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import static org.junit.jupiter.api.Assertions.*;

class OAuthUserInfoMapperTests {

    private final OAuthUserInfoMapper mapper = new OAuthUserInfoMapper();

    @Test
    void mapsGoogleProfile() {
        OAuthUserInfo result = mapper.map("google", Map.of(
                "sub", "google-user", "email", "user@example.com",
                "name", "Tester", "picture", "https://example.com/profile.png"));
        assertEquals(new OAuthUserInfo("google", "google-user", "user@example.com",
                "Tester", "https://example.com/profile.png"), result);
    }

    @Test
    void mapsKakaoNestedProfileAndNumericId() {
        OAuthUserInfo result = mapper.map("kakao", Map.of("id", 1234567890123L,
                "kakao_account", Map.of("email", "user@example.com", "profile",
                        Map.of("nickname", "Tester", "profile_image_url", "https://example.com/profile.png"))));
        assertEquals(new OAuthUserInfo("kakao", "1234567890123", "user@example.com",
                "Tester", "https://example.com/profile.png"), result);
    }

    @Test
    void acceptsKakaoWithoutOptionalConsent() {
        assertEquals(new OAuthUserInfo("kakao", "42", null, null, null),
                mapper.map("kakao", Map.of("id", 42L)));
    }

    @Test
    void acceptsKakaoAccountWithoutProfileOrEmail() {
        assertEquals(new OAuthUserInfo("kakao", "42", null, null, null),
                mapper.map("kakao", Map.of("id", 42L, "kakao_account", Map.of())));
    }

    @Test
    void acceptsGoogleWithoutOptionalProfile() {
        assertEquals(new OAuthUserInfo("google", "google-user", null, null, null),
                mapper.map("google", Map.of("sub", "google-user")));
    }

    @Test
    void rejectsMissingOrBlankIdentity() {
        assertThrows(OAuth2AuthenticationException.class, () -> mapper.map("google", Map.of()));
        assertThrows(OAuth2AuthenticationException.class, () -> mapper.map("kakao", Map.of()));
        assertThrows(OAuth2AuthenticationException.class,
                () -> mapper.map("google", Map.of("sub", " ")));
    }

    @Test
    void rejectsUnsupportedProvider() {
        assertThrows(OAuth2AuthenticationException.class, () -> mapper.map("unknown", Map.of("id", 1)));
    }
}
