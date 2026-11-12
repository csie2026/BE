package com.ggmount.global.auth.oauth;

import java.io.Serializable;

/**
 * 공급자 공통 정보. 동의하지 않거나 제공되지 않은 선택 정보는 null입니다.
 * provider와 providerId는 공급자 측 계정을 식별하며, 서비스 내부 회원 ID와는 구분됩니다.
 * 필수 식별자 검증은 이 자료형 자체가 아니라 공급자 응답을 변환하는 매퍼에서 수행합니다.
 */
public record OAuthUserInfo(
    String provider,
    String providerId,
    String email,
    String nickname,
    String profileImage
) implements Serializable {
}
