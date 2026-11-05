package com.ggmount.hiking.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.List;

public record HikingActivityRequest(
    @NotNull @Positive Long mountainId,
    @Positive Long courseId,
    @NotNull @PastOrPresent Instant startedAt,
    @NotNull @PastOrPresent Instant endedAt,
    @NotNull @PositiveOrZero Double distanceMeters,
    @NotNull @PositiveOrZero Long elapsedMs,
    @NotNull Boolean completed,
    @NotNull @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientRequestId,
    // Required by the service for new activities, optional for legacy UUID retries.
    List<List<GpsPointRequest>> gpsSegments
) {
    // Framework diagnostic logging must not print GPS evidence or request contents.
    @Override public String toString() { return "HikingActivityRequest[redacted]"; }

    // Called after request validation, so rounding cannot hide invalid input.
    public HikingActivityRequest normalizedForStorage() {
        return new HikingActivityRequest(mountainId, courseId, postgresPrecision(startedAt),
            postgresPrecision(endedAt), distanceMeters, elapsedMs, completed,
            clientRequestId.toLowerCase(Locale.ROOT), gpsSegments);
    }

    private static Instant postgresPrecision(Instant value) {
        // Match pgjdbc's nanosecond -> microsecond rounding, including second carry.
        return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    @AssertTrue(message = "산행 시각, 거리와 소요 시간을 확인해주세요.")
    public boolean isSessionValid() {
        return startedAt != null && endedAt != null && elapsedMs != null && distanceMeters != null
            && !endedAt.isBefore(startedAt) && Double.isFinite(distanceMeters)
            && elapsedMs <= java.time.Duration.between(startedAt, endedAt).toMillis();
    }
}
