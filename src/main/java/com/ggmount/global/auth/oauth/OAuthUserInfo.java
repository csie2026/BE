package com.ggmount.global.auth.oauth;

import java.io.Serializable;

/** 공급자 공통 정보. 동의하지 않거나 제공되지 않은 선택 정보는 null입니다. */
public record OAuthUserInfo(String provider, String providerId, String email,
                            String nickname, String profileImage) implements Serializable {
}
