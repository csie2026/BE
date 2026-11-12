package com.ggmount.global.auth.oauth;

import com.ggmount.member.service.OAuthMemberService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/** 공급자 사용자 정보 조회와 내부 회원 처리를 연결하고, 내부 회원 ID를 포함한 인증 주체를 구성한다. */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final OAuthUserInfoMapper mapper;
    private final OAuthMemberService memberService;

    public CustomOAuth2UserService(OAuthUserInfoMapper mapper, OAuthMemberService memberService) {
        this.mapper = mapper;
        this.memberService = memberService;
    }

    /**
     * 공급자 조회·매핑·회원 저장 후 내부 회원 ID를 포함한 인증 주체를 구성한다.
     * @throws OAuth2AuthenticationException 공급자 조회 또는 필수 식별자 검증 실패
     */
    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();
        OAuthUserInfo userInfo = mapper.map(provider, user.getAttributes());
        var member = memberService.processLogin(userInfo);
        String nameAttribute = request.getClientRegistration().getProviderDetails()
        .getUserInfoEndpoint().getUserNameAttributeName();
        return new OAuthPrincipal(user.getAuthorities(), user.getAttributes(), nameAttribute, userInfo, member.getId());
    }
}
