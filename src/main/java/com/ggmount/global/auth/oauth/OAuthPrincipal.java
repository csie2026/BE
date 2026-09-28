package com.ggmount.global.auth.oauth;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/** 원본 속성과 공통 사용자 정보를 함께 보관하는 세션 principal입니다. */
public class OAuthPrincipal extends DefaultOAuth2User {

    private final OAuthUserInfo userInfo;

    public OAuthPrincipal(Collection<? extends GrantedAuthority> authorities,
            Map<String, Object> attributes, String nameAttributeKey, OAuthUserInfo userInfo) {
        super(authorities, attributes, nameAttributeKey);
        this.userInfo = userInfo;
    }

    public OAuthUserInfo getUserInfo() {
        return userInfo;
    }

    @Override
    public String getName() {
        return userInfo.provider() + ":" + userInfo.providerId();
    }
}
