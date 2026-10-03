package com.ggmount.member.service;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.domain.Member;
import com.ggmount.member.dto.*;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository repository;
    private final com.ggmount.member.repository.PersonalDataRepository personalData;
    public MemberService(
        MemberRepository repository,
        com.ggmount.member.repository.PersonalDataRepository personalData
    ) {
        this.repository = repository;
        this.personalData = personalData;
    }
    public MemberResponse me(OAuthPrincipal principal) {
        return response(current(principal));
    }
    private MemberResponse response(Member member) {
        return MemberResponse.withImages(member, personalData.imageRevisions(member.getId()));
    }
    // 요청의 임의 회원 ID 대신 세션의 OAuth 식별자로 내부 회원을 찾아 본인 데이터의 기준으로 삼는다.
    public Member current(OAuthPrincipal principal) {
        if (principal == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var info = principal.getUserInfo();
        return repository.findByProviderAndProviderId(info.provider(), info.providerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    public Member find(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @Transactional
    // 요청 DTO 검증을 통과한 닉네임·출생연도를 저장하고 프로필 완료 상태로 전환한다.
    public MemberResponse update(OAuthPrincipal p, MemberUpdateRequest request) {
        Member member = current(p);
        member.completeProfile(request.nickname(), request.birthYear());
        return response(member);
    }
    // 프로필 미완료 계정은 일지 작성이나 공개 프로필 조회 대상이 되지 않도록 서비스에서 제한한다.
    public void requireComplete(Member m) {
        if (!m.isProfileCompleted())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로필 설정이 필요합니다.");
    }
}
