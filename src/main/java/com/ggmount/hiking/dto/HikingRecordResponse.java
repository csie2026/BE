package com.ggmount.hiking.dto;
import com.ggmount.hiking.domain.HikingRecord;
import java.time.LocalDate;
public record HikingRecordResponse(
    Long id,
    Long userId,
    String nickname,
    String mountainName,
    String title,
    String content,
    LocalDate hikingDate,
    boolean isPublic,
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
