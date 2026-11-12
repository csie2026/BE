package com.ggmount.hiking.dto;

import java.time.Instant;

/**
 * 클라이언트가 경로 구간에 제출한 좌표로 기기의 실제 위치를 보증하지 않는다.
 * 위도·경도는 도 단위, 측정 시각은 Instant, 정확도는 미터 단위다.
 * DTO 자체에 검증 어노테이션은 없으며 값의 존재·범위·시각 순서는 서비스에서 검증한다.
 */
public record GpsPointRequest(Double lat, Double lng, Instant measuredAt, Double accuracyMeters) {
    // 문자열 변환 결과에는 좌표·시각·정확도를 포함하지 않는다.
    @Override public String toString() { return "GpsPointRequest[redacted]"; }
}
