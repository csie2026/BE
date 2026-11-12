package com.ggmount.global.auth.oauth;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * 원본 속성과 공통 사용자 정보를 함께 보관하는 세션 principal입니다.
 * 공급자의 권한·속성은 상위 클래스에 전달하고, 공급자 계정 정보와 내부 회원 ID는 별도로 보관합니다.
 */
public class OAuthPrincipal extends DefaultOAuth2User {

    private final OAuthUserInfo userInfo;
    private final Long memberId;

    /** 내부 회원 ID 없이 생성하며, 이름 식별에는 공급자와 공급자 사용자 ID의 조합을 사용한다. */
    public OAuthPrincipal(
        Collection<? extends GrantedAuthority> authorities,
        Map<String,
        Object> attributes,
        String nameAttributeKey,
        OAuthUserInfo userInfo
    ) {
        this(authorities, attributes, nameAttributeKey, userInfo, null);
    }

    public OAuthPrincipal(Collection<? extends GrantedAuthority> authorities, Map<String, Object> attributes,
        String nameAttributeKey, OAuthUserInfo userInfo, Long memberId) {
        super(authorities, attributes, nameAttributeKey);
        this.userInfo = userInfo;
        this.memberId = memberId;
    }

    public Long getMemberId() { return memberId; }

    public OAuthUserInfo getUserInfo() {
        return userInfo;
    }

    @Override
    // 인증 클라이언트 저장소의 이름을 내부 회원 ID에 연결해 탈퇴 전 토큰이 재가입 계정에 연결되지 않게 한다.
    // 내부 회원 ID가 없는 경우에만 공급자와 공급자 사용자 ID의 조합을 사용한다.
    public String getName() {
        return memberId == null ? userInfo.provider() + ":" + userInfo.providerId() : "topeak:" + memberId;
    }
}
