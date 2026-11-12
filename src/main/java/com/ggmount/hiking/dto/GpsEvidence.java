package com.ggmount.hiking.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

/**
 * 클라이언트에서 선별해 제출한 단일 GPS 표본이며 전체 이동 경로나 실제 현장 방문의 증명은 아니다.
 * 위도·경도는 도 단위, 정확도는 미터 단위, 기록 시각은 미래가 아닌 Instant 값이다.
 */
public record GpsEvidence(
    @NotNull Double latitude, @NotNull Double longitude,
    @NotNull Double accuracyMeters, @NotNull @PastOrPresent Instant recordedAt
) {
    // 문자열 변환 결과에는 표본의 좌표·정확도·시각을 포함하지 않는다.
    @Override public String toString() { return "GpsEvidence[redacted]"; }

    // 표본 내부 값은 모두 필수이며 유한한 위도 ±90도·경도 ±180도, 정확도 0~50m를 검사한다.
    @AssertTrue(message = "유효한 GPS 표본을 확인해주세요.")
    public boolean isSampleValid() {
        return latitude != null && longitude != null && accuracyMeters != null && recordedAt != null
            && Double.isFinite(latitude) && Math.abs(latitude) <= 90
            && Double.isFinite(longitude) && Math.abs(longitude) <= 180
            && Double.isFinite(accuracyMeters) && accuracyMeters >= 0 && accuracyMeters <= 50;
    }
}
