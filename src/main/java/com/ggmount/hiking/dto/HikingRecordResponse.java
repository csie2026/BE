package com.ggmount.hiking.dto;
import com.ggmount.hiking.domain.HikingRecord;
import java.time.LocalDate;
/**
 * id는 일지 ID, userId는 내부 회원 ID다. 공개 여부에 따른 조회 허용은 서비스에서 판단한다.
 */
public record HikingRecordResponse(
    Long id,
    Long userId,
    String nickname,
    String mountainName,
    String title,
    String content,
    LocalDate hikingDate,
    boolean isPublic,
    // 연결된 산행 활동 ID이며, 활동 연결이 없는 기존 일지는 null이다. 일지 미작성 상태를 뜻하지 않는다.
    Long hikingRecordId
) {
    public static HikingRecordResponse from(HikingRecord r) {
        return new HikingRecordResponse(
            r.getId(),
            r.getMember().getId(),
            r.getMember().getNickname(),
            r.getMountainName(),
            r.getTitle(),
            r.getContent(),
            r.getHikingDate(),
            r.isPublicRecord(),
            r.getActivity() == null ? null : r.getActivity().getId()
        );
    }
}
