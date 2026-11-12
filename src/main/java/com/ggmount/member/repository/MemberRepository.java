package com.ggmount.member.repository;
import com.ggmount.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
public interface MemberRepository extends JpaRepository<Member, Long> {
    // 내부 회원 ID 대신 공급자와 해당 공급자의 사용자 ID 조합으로 연결된 회원을 조회한다.
    Optional<Member> findByProviderAndProviderId(String provider, String providerId);
    // 세션 검증을 위해 내부 회원 ID와 외부 OAuth 식별 정보가 모두 일치하는 회원의 존재 여부를 확인한다.
    boolean existsByIdAndProviderAndProviderId(Long id, String provider, String providerId);
    // 로그인 처리에서 공급자 계정에 해당하는 기존 회원 행을 비관적 쓰기 잠금으로 조회한다.
    // 아직 존재하지 않는 회원 행의 생성까지 이 조회로 잠그는 것은 아니다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.provider=:provider and m.providerId=:providerId")
    Optional<Member> lockForLogin(@org.springframework.data.repository.query.Param("provider") String provider,
        @org.springframework.data.repository.query.Param("providerId") String providerId);
    // 프로필 완료 회원을 대상으로 미산정 점수는 뒤로 보내고, 같은 점수는 회원 ID로 순서를 고정한다.
    // 산정된 점수는 내림차순, 내부 회원 ID는 오름차순이며 조회 범위는 전달된 페이지 정보로 제한한다.
    @org.springframework.data.jpa.repository.Query(
        "select m from Member m where m.profileCompleted=true order by case when m.score is null then 1 else 0 end, m.score desc, m.id asc"
    )
    List<Member> findRanking(org.springframework.data.domain.Pageable pageable);
}
