package com.ggmount.member.service;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import com.ggmount.member.domain.Member;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OAuthMemberService {
    private final MemberRepository repository;
    public OAuthMemberService(MemberRepository repository) {
        this.repository = repository;
    }
    @Transactional
    // 공급자와 외부 사용자 ID의 조합으로 기존 회원을 찾는다.
    // 재로그인은 OAuth 정보만 갱신해 사용자가 완료한 닉네임·출생연도 설정을 덮어쓰지 않는다.
    public Member processLogin(OAuthUserInfo info) {
        Member member = repository.findByProviderAndProviderId(info.provider(), info.providerId())
            .orElseGet(() -> new Member(info));
        member.refreshOAuth(info);
        return repository.save(member);
    }
}
