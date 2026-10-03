package com.ggmount.global.auth.oauth;

import com.ggmount.member.service.OAuthMemberService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final OAuthUserInfoMapper mapper;
    private final OAuthMemberService memberService;

    public CustomOAuth2UserService(OAuthUserInfoMapper mapper, OAuthMemberService memberService) {
        this.mapper = mapper;
        this.memberService = memberService;
    }

    @Override
    // 공급자 사용자 정보를 공통 형식으로 변환해 내부 회원을 생성·갱신한 뒤 세션에서 사용할 principal을 만든다.
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();
        OAuthUserInfo userInfo = mapper.map(provider, user.getAttributes());
        memberService.processLogin(userInfo);
        String nameAttribute = request.getClientRegistration().getProviderDetails()
        .getUserInfoEndpoint().getUserNameAttributeName();
        return new OAuthPrincipal(user.getAuthorities(), user.getAttributes(), nameAttribute, userInfo);
    }
}
