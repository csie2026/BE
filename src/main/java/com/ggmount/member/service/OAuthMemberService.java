package com.ggmount.member.service;

import com.ggmount.global.auth.oauth.OAuthUserInfo;
import org.springframework.stereotype.Service;

/** DB 및 회원 모델 확정 후 소셜 계정과 서비스 회원을 연결할 경계입니다. */
@Service
public class OAuthMemberService {

    public void processLogin(OAuthUserInfo userInfo) {
        // TODO: provider + providerId로 기존 회원 조회 (email로 자동 연결하지 않음)
        // TODO: 없으면 신규 회원 생성
        // TODO: 있으면 기존 회원 반환. 회원 모델 확정 후 반환 타입 및 principal 연결 구현
    }
}
