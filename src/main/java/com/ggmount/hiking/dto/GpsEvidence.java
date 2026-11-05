package com.ggmount.hiking.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

/** One FE-filtered original sample, not proof of physical presence or an entire GPS route. */
public record GpsEvidence(
    @NotNull Double latitude, @NotNull Double longitude,
    @NotNull Double accuracyMeters, @NotNull @PastOrPresent Instant recordedAt
) {
    @AssertTrue(message = "유효한 GPS 표본을 확인해주세요.")
    public boolean isSampleValid() {
        return latitude != null && longitude != null && accuracyMeters != null && recordedAt != null
            && Double.isFinite(latitude) && Math.abs(latitude) <= 90
            && Double.isFinite(longitude) && Math.abs(longitude) <= 180
            && Double.isFinite(accuracyMeters) && accuracyMeters >= 0 && accuracyMeters <= 50;
    }
}
