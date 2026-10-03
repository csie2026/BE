package com.ggmount.hiking.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record JournalUpdateRequest(
    @NotBlank @Size(max = 100) String title,
    @NotNull @Size(max = 10000) String content,
    @NotNull Boolean isPublic,
    @Null(message = "연결된 등산기록은 변경할 수 없습니다.") Long hikingRecordId,
    @Null(message = "산은 변경할 수 없습니다.") String mountainName,
    @Null(message = "등산 날짜는 변경할 수 없습니다.") LocalDate hikingDate
) {}
