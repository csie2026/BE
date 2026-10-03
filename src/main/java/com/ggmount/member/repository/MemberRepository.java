package com.ggmount.member.repository;
import com.ggmount.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByProviderAndProviderId(String provider, String providerId);
    @org.springframework.data.jpa.repository.Query(
        "select m from Member m where m.profileCompleted=true order by case when m.score is null then 1 else 0 end, m.score desc, m.id asc"
    )
    List<Member> findRanking(org.springframework.data.domain.Pageable pageable);
}
