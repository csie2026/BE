package com.ggmount.hiking.repository;

import com.ggmount.hiking.domain.HikingActivity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface HikingActivityRepository extends JpaRepository<HikingActivity, Long> {
    List<HikingActivity> findByMemberIdOrderByStartedAtDescIdDesc(Long memberId);
    Optional<HikingActivity> findByIdAndMemberId(Long id, Long memberId);
    Optional<HikingActivity> findByMemberIdAndClientRequestId(Long memberId, String clientRequestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from HikingActivity a where a.id=:id and a.member.id=:memberId")
    Optional<HikingActivity> lockOwned(@Param("id") Long id, @Param("memberId") Long memberId);
}
