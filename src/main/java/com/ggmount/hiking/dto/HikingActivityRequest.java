package com.ggmount.hiking.dto;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.List;

/** 클라이언트가 제출한 산행 정보이며, GPS 경로·거리 검증과 정상 도달 판정은 서비스에서 별도로 수행한다. */
public record HikingActivityRequest(
    @NotNull @Positive Long mountainId,
    @Positive Long courseId,
    // 시작·종료 시각은 필수이며 미래 시각을 허용하지 않는다.
    @NotNull @PastOrPresent Instant startedAt,
    @NotNull @PastOrPresent Instant endedAt,
    // 제출 거리의 단위는 미터, 경과 시간은 밀리초이며 두 값 모두 필수·음수 불가다.
    @NotNull @PositiveOrZero Double distanceMeters,
    @NotNull @PositiveOrZero Long elapsedMs,
    // 클라이언트가 제출한 도달 여부로, 저장되는 정상 도달 여부는 서버가 GPS 경로로 별도 판정한다.
    @NotNull Boolean completed,
    @NotNull @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientRequestId,
    // 단일 GPS 표본은 선택 사항이며 제공되면 중첩 검증한다.
    @Valid GpsEvidence gpsEvidence,
    // 서비스는 신규 저장에 경로 구간을 요구하지만, 이전 GPS 검증 버전이 없는 기록의 재요청에서는 생략할 수 있다.
    // 구간·좌표의 개수, 범위와 시각 순서는 DTO 어노테이션이 아닌 서비스에서 검증한다.
    // 거리 증거 조건을 충족하면 서비스에서 구간 내부 인접 좌표 간 거리 합과 제출 거리를 대조한다.
    List<List<GpsPointRequest>> gpsSegments
) {
    // 단일 표본이 있으면 기록 시각이 산행 시작·종료 시각 사이인지 양 끝을 포함해 확인한다.
    @AssertTrue(message = "GPS 표본은 산행 시작과 종료 사이여야 합니다.")
    public boolean isGpsWithinSession() {
        return gpsEvidence == null || (gpsEvidence.recordedAt() != null && startedAt != null && endedAt != null
            && !gpsEvidence.recordedAt().isBefore(startedAt) && !gpsEvidence.recordedAt().isAfter(endedAt));
    }

    // 문자열 변환 시 GPS 표본·경로와 요청 내용 대신 고정 문구를 반환한다.
    @Override public String toString() { return "HikingActivityRequest[redacted]"; }

    // 요청 검증 이후 저장용으로 호출하며, 시각 정밀도를 맞추고 요청 UUID를 소문자로 정규화한다.
    public HikingActivityRequest normalizedForStorage() {
        return new HikingActivityRequest(mountainId, courseId, postgresPrecision(startedAt),
            postgresPrecision(endedAt), distanceMeters, elapsedMs, completed,
            clientRequestId.toLowerCase(Locale.ROOT), gpsEvidence, gpsSegments);
    }

    private static Instant postgresPrecision(Instant value) {
        // 초 경계의 올림을 포함해 PostgreSQL JDBC와 같은 나노초→마이크로초 반올림을 적용한다.
        return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    // 종료가 시작보다 이르지 않고 거리가 유한한지, 경과 시간이 전체 시각 차이를 넘지 않는지 확인한다.
    @AssertTrue(message = "산행 시각, 거리와 소요 시간을 확인해주세요.")
    public boolean isSessionValid() {
        return startedAt != null && endedAt != null && elapsedMs != null && distanceMeters != null
            && !endedAt.isBefore(startedAt) && Double.isFinite(distanceMeters)
            && elapsedMs >= 0 && distanceMeters >= 0 && elapsedFits();
    }
    private boolean elapsedFits() {
        try { return elapsedMs <= java.time.Duration.between(startedAt, endedAt).toMillis(); }
        catch (ArithmeticException invalidRange) { return false; }
    }
}
