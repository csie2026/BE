package com.ggmount.hiking.repository;
import com.ggmount.hiking.domain.HikingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface HikingRecordRepository extends JpaRepository<HikingRecord, Long> {
    List<HikingRecord> findByMemberIdOrderByHikingDateDescIdDesc(Long id);
    List<HikingRecord> findByMemberIdAndPublicRecordTrueOrderByHikingDateDescIdDesc(Long id);
    List<HikingRecord> findTop100ByPublicRecordTrueOrderByHikingDateDescIdDesc();
    // 수정·삭제 시 존재 여부와 소유권을 하나의 조회 조건으로 확인하는 메서드다.
    Optional<HikingRecord> findByIdAndMemberId(Long id, Long memberId);
}
