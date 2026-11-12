package com.ggmount.global.auth.oauth;

import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

/** 공급자별 사용자 응답을 공통 정보로 변환하고 계정 식별에 필요한 사용자 ID를 검증한다. */
@Component
public class OAuthUserInfoMapper {

    /**
     * Google의 sub 또는 Kakao의 id를 공급자 사용자 ID로 사용한다.
     * 선택 정보는 문자열인 경우에만 반영하며, 누락되거나 형식이 다르면 null로 처리한다.
     *
     * @throws OAuth2AuthenticationException 지원하지 않는 공급자이거나 필수 사용자 ID가 유효하지 않은 경우
     */
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

    /** 숫자형 ID도 문자열로 통일하되, 식별할 수 없는 계정으로 로그인을 이어가지 않도록 누락·공백을 거부한다. */
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

    // 선택 정보의 중첩 객체가 없거나 객체 형식이 아니면 빈 맵으로 처리해 하위 정보도 누락된 값으로 변환한다.
    private Map<?, ?> nested(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}
