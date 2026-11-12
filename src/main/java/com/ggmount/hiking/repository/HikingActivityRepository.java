package com.ggmount.hiking.repository;

import com.ggmount.hiking.domain.HikingActivity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface HikingActivityRepository extends JpaRepository<HikingActivity, Long> {
    // 내부 회원 ID에 속한 산행을 시작 시각 내림차순으로 조회하고, 같은 시각이면 산행 ID 내림차순으로 정렬한다.
    List<HikingActivity> findByMemberIdOrderByStartedAtDescIdDesc(Long memberId);
    // 산행 ID와 내부 회원 ID를 함께 대조해 본인 산행의 상세 조회에 사용한다.
    Optional<HikingActivity> findByIdAndMemberId(Long id, Long memberId);
    // 같은 회원의 요청 ID를 대소문자 구분 없이 조회한다. 여러 후보나 내용 불일치의 충돌 판정은 서비스가 수행한다.
    List<HikingActivity> findAllByMemberIdAndClientRequestIdIgnoreCase(Long memberId, String clientRequestId);
    // 요청 ID와 무관하게 회원·산·시작·종료 시각이 모두 같은 산행의 존재 여부를 확인한다.
    boolean existsByMemberIdAndMountainIdAndStartedAtAndEndedAt(Long memberId, Long mountainId,
        java.time.Instant startedAt, java.time.Instant endedAt);
    // 일지 작성 트랜잭션에서 본인 산행을 비관적 쓰기 잠금으로 조회한다. 조회되지 않은 산행까지 잠그는 조건은 아니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from HikingActivity a where a.id=:id and a.member.id=:memberId")
    Optional<HikingActivity> lockOwned(@Param("id") Long id, @Param("memberId") Long memberId);
}
