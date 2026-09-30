package com.ggmount.hiking.repository;
import com.ggmount.hiking.domain.HikingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface HikingRecordRepository extends JpaRepository<HikingRecord,Long> {
 List<HikingRecord> findByMemberIdOrderByHikingDateDescIdDesc(Long id);
 List<HikingRecord> findByMemberIdAndPublicRecordTrueOrderByHikingDateDescIdDesc(Long id);
 List<HikingRecord> findTop100ByPublicRecordTrueOrderByHikingDateDescIdDesc();
 Optional<HikingRecord> findByIdAndMemberId(Long id,Long memberId);
}
