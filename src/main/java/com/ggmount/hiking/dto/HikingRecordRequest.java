package com.ggmount.hiking.dto;
import jakarta.validation.constraints.*;
public record HikingRecordRequest(
    @NotNull @Positive Long hikingRecordId,
    @NotBlank @Size(max = 100) String title,
    @NotNull @Size(max = 10000) String content,
    @NotNull Boolean isPublic
) {
}
