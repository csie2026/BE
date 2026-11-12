package com.ggmount.hiking.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/**
 * 제목·내용·공개 여부를 모두 교체하는 요청으로, 생략·null을 기존 값 유지로 처리하지 않는다.
 * 내용은 빈 문자열을 허용하며 연결된 활동·산·날짜는 변경할 수 없다.
 */
public record JournalUpdateRequest(
    @NotBlank @Size(max = 100) String title,
    @NotNull @Size(max = 10000) String content,
    @NotNull Boolean isPublic,
    @Null(message = "연결된 등산기록은 변경할 수 없습니다.") Long hikingRecordId,
    @Null(message = "산은 변경할 수 없습니다.") String mountainName,
    @Null(message = "등산 날짜는 변경할 수 없습니다.") LocalDate hikingDate
) {}
