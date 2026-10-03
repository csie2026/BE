package com.ggmount.global.auth.oauth;

import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

@Component
public class OAuthUserInfoMapper {

    // Google의 평면 응답과 Kakao의 중첩 응답을 공통 회원 정보로 맞춘다.
    // 동의하지 않은 선택 정보는 없을 수 있지만 공급자의 사용자 ID는 계정 식별을 위해 필수다.
    public OAuthUserInfo map(String provider, Map<String, Object> attributes) {
        return switch (provider) {
            case "google" -> new OAuthUserInfo(
                provider,
                requiredId(attributes.get("sub")),
                string(attributes.get("email")),
                string(attributes.get("name")),
                string(attributes.get("picture"))
            );
            case "kakao" -> {
                Map<?, ?> account = nested(attributes.get("kakao_account"));
                Map<?, ?> profile = nested(account.get("profile"));
                yield new OAuthUserInfo(
                    provider,
                    requiredId(attributes.get("id")),
                    string(account.get("email")),
                    string(profile.get("nickname")),
                    string(profile.get("profile_image_url"))
                );
            }
            default -> throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        }
        ;
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
