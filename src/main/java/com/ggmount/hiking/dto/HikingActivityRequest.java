package com.ggmount.hiking.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record HikingActivityRequest(
    @NotNull @Positive Long mountainId,
    @Positive Long courseId,
    @NotNull @PastOrPresent Instant startedAt,
    @NotNull @PastOrPresent Instant endedAt,
    @NotNull @PositiveOrZero Double distanceMeters,
    @NotNull @PositiveOrZero Long elapsedMs,
    @NotNull Boolean completed,
    @NotNull @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientRequestId
) {
    @AssertTrue(message = "산행 시각, 거리와 소요 시간을 확인해주세요.")
    public boolean isSessionValid() {
        return startedAt != null && endedAt != null && elapsedMs != null && distanceMeters != null
            && !endedAt.isBefore(startedAt) && Double.isFinite(distanceMeters)
            && elapsedMs <= java.time.Duration.between(startedAt, endedAt).toMillis();
    }
}
