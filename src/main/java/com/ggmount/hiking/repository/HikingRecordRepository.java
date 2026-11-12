package com.ggmount.hiking.repository;
import com.ggmount.hiking.domain.HikingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface HikingRecordRepository extends JpaRepository<HikingRecord, Long> {
    // 산행 응답에 연결할 일지를 활동 ID로 조회하며, 이 조건 자체에는 소유권·공개 여부 제한이 없다.
    Optional<HikingRecord> findByActivityId(Long activityId);
    // 일지 생성 전에 해당 산행 활동에 이미 일지가 연결되었는지 확인한다. 이 조회 자체에 잠금은 없다.
    boolean existsByActivityId(Long activityId);
    List<HikingRecord> findByMemberIdOrderByHikingDateDescIdDesc(Long id);
    List<HikingRecord> findByMemberIdAndPublicRecordTrueOrderByHikingDateDescIdDesc(Long id);
    List<HikingRecord> findTop100ByPublicRecordTrueOrderByHikingDateDescIdDesc();
    // 수정·삭제 시 존재 여부와 소유권을 하나의 조회 조건으로 확인하는 메서드다.
    Optional<HikingRecord> findByIdAndMemberId(Long id, Long memberId);
}
