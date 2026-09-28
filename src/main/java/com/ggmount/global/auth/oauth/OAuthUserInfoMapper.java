package com.ggmount.global.auth.oauth;

import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

@Component
public class OAuthUserInfoMapper {

    public OAuthUserInfo map(String provider, Map<String, Object> attributes) {
        return switch (provider) {
            case "google" -> new OAuthUserInfo(provider, requiredId(attributes.get("sub")),
                    string(attributes.get("email")), string(attributes.get("name")),
                    string(attributes.get("picture")));
            case "kakao" -> {
                Map<?, ?> account = nested(attributes.get("kakao_account"));
                Map<?, ?> profile = nested(account.get("profile"));
                yield new OAuthUserInfo(provider, requiredId(attributes.get("id")),
                        string(account.get("email")), string(profile.get("nickname")),
                        string(profile.get("profile_image_url")));
            }
            default -> throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        };
    }

    private String requiredId(Object value) {
        String id = value instanceof Number ? value.toString() : string(value);
        if (id == null || id.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"));
        }
        return id;
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private Map<?, ?> nested(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}
