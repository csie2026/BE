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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public MemberService(
        MemberRepository repository,
        com.ggmount.member.repository.PersonalDataRepository personalData,
        org.springframework.jdbc.core.JdbcTemplate jdbc
    ) {
        this.repository = repository;
        this.personalData = personalData;
        this.jdbc = jdbc;
    }
    public MemberResponse me(OAuthPrincipal principal) {
        return response(current(principal));
    }
    private MemberResponse response(Member member) {
        return MemberResponse.withImages(member, personalData.imageRevisions(member.getId()));
    }
    // 세션의 내부 회원 ID로 조회한 뒤 공급자·공급자 사용자 ID도 일치하는 회원을 본인 데이터의 기준으로 삼는다.
    // principal 또는 memberId가 없거나, 조회 회원이 없거나 공급자 정보가 다르면 401로 거부한다.
    public Member current(OAuthPrincipal principal) {
        if (principal == null || principal.getMemberId() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var info = principal.getUserInfo();
        return repository.findById(principal.getMemberId())
            .filter(member -> member.getProvider().equals(info.provider()) && member.getProviderId().equals(info.providerId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    // 대상 회원이 없으면 404, 프로필 설정이 미완료이면 403으로 거부하고 공개 응답만 구성한다.
    public PublicProfileResponse publicProfile(Long id) {
        Member member = find(id);
        requireComplete(member);
        String revision = personalData.profileRevisions(java.util.List.of(id)).get(id);
        // 점수 상태·원장·일지와 독립적으로 저장 거리·시간 또는 서버 검증 완등을 활동마다 집계한다.
        // GPS 검증 버전 1의 completed만 서버 판정 근거가 있으며, 구버전 완료 플래그는 제외한다.
        return jdbc.queryForObject("""
            SELECT COUNT(CASE WHEN (distance_meters >= 500 AND elapsed_ms >= 1200000)
                    OR (completed = TRUE AND gps_validation_version = 1) THEN 1 END) AS total_hikes,
                COUNT(CASE WHEN completed = TRUE AND gps_validation_version = 1 THEN 1 END) AS completed_hikes
            FROM hiking_activities WHERE member_id = ?
            """, (row, index) -> PublicProfileResponse.from(
                PublicMemberResponse.withImage(member, revision),
                row.getLong("total_hikes"), row.getLong("completed_hikes")), id);
    }
    public Member find(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @Transactional
    // 요청 DTO 검증을 통과한 닉네임·출생연도를 저장하고 프로필 완료 상태로 전환한다.
    public MemberResponse update(OAuthPrincipal p, MemberUpdateRequest request) {
        Member member = current(p);
        // 수정 대상은 Principal로만 결정하며, 예상 ID는 편집 대상 일치 여부 확인에만 사용한다.
        if (!member.getId().equals(request.expectedUserId())) throw new ProfileAccountMismatch();
        member.completeProfile(request.nickname(), request.birthYear());
        return response(member);
    }
    // 프로필 미완료 계정은 일지 작성이나 공개 프로필 조회 대상이 되지 않도록 서비스에서 제한한다.
    public void requireComplete(Member m) {
        if (!m.isProfileCompleted())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로필 설정이 필요합니다.");
    }
}
