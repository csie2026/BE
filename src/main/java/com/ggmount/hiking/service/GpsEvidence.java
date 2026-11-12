package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.HikingActivityRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

/** 제출된 GPS 경로의 일관성을 검사하며, 좌표가 기기의 실제 위치나 실제 이동을 증명한다고 보장하지 않는다. */
public final class GpsEvidence {
    // 저장되는 GPS 검증 버전은 1을 유지하며 신규 저장에서는 거리 대조도 별도로 수행한다.
    public static final short VALIDATION_VERSION = 1;
    public static final int MAX_POINTS = 10_000;
    public static final int MAX_SEGMENTS = 10_000;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private GpsEvidence() {}

    /** 저장용 시각 반올림 전의 원본 요청을 검사하며, 검증 오류 응답에 GPS 원본 값을 포함하지 않는다. */
    public static void validate(HikingActivityRequest request) {
        var segments = request.gpsSegments();
        // 경로는 필수이며 구간 수와 전체 표본 수를 각각 1만 개 이하로 제한한다. 빈 경로·빈 구간은 허용한다.
        if (segments == null || segments.size() > MAX_SEGMENTS) throw invalid();
        int count = 0;
        Instant previous = request.startedAt();
        for (var segment : segments) {
            if (segment == null || segment.size() > MAX_POINTS - count) throw invalid();
            count += segment.size();
            for (var point : segment) {
                // 위도·경도는 유한한 도 단위 값으로 ±90도·±180도 이내, 정확도는 유한한 0m 이상이어야 한다.
                // 이 단계에는 정확도 상한이 없으며, 시각은 구간 경계를 포함해 비감소 순서이고 산행 범위 안이어야 한다.
                if (point == null || !finite(point.lat()) || Math.abs(point.lat()) > 90
                    || !finite(point.lng()) || Math.abs(point.lng()) > 180
                    || !finite(point.accuracyMeters()) || point.accuracyMeters() < 0
                    || point.measuredAt() == null || point.measuredAt().isBefore(previous)
                    || point.measuredAt().isAfter(request.endedAt())) throw invalid();
                previous = point.measuredAt();
            }
        }
    }

    /** 검증된 경로에서 정확도 50m 이하인 표본의 존재 여부를 확인하며 선택적 단일 표본은 사용하지 않는다. */
    public static boolean hasUsablePoint(HikingActivityRequest request) {
        if (request.gpsSegments() == null) return false;
        return request.gpsSegments().stream().flatMap(java.util.Collection::stream)
            .anyMatch(point -> point.accuracyMeters() <= 50);
    }

    /**
     * 모든 표본의 정확도가 50m 이하이고 구간 내부에 거리가 0보다 큰 인접 좌표 쌍이 있어야 거리 증거로 인정한다.
     * 조건을 충족하지 않으면 거리 대조를 생략하며, 이 메서드 자체가 저장 요청을 거부하지는 않는다.
     */
    public static boolean hasDistanceEvidence(HikingActivityRequest request) {
        if (request.gpsSegments() == null) return false;
        boolean edge = false;
        for (var segment : request.gpsSegments()) {
            for (var point : segment) if (point.accuracyMeters() > 50) return false;
            for (int i = 1; i < segment.size(); i++)
                edge |= distanceMeters(segment.get(i - 1).lat(), segment.get(i - 1).lng(),
                    segment.get(i).lat(), segment.get(i).lng()) > 0;
        }
        return edge;
    }

    // 반올림하지 않은 좌표로 구간 내부 인접 표본 간 거리를 합산한다. 서로 다른 구간 사이는 연결하지 않는다.
    public static double routeDistance(HikingActivityRequest request) {
        double total = 0;
        for (var segment : request.gpsSegments()) {
            double subtotal = 0;
            for (int i = 1; i < segment.size(); i++) {
                var a = segment.get(i - 1);
                var b = segment.get(i);
                subtotal += distanceMeters(a.lat(), a.lng(), b.lat(), b.lng());
            }
            total += subtotal;
        }
        return total;
    }

    // 거리 증거가 있을 때만 제출 거리와 대조하며, 오차가 1m와 계산 거리의 백만분의 일 중 큰 값을 넘으면 400이다.
    public static void validateDistance(HikingActivityRequest request) {
        if (!hasDistanceEvidence(request)) return;
        double distance = routeDistance(request);
        double tolerance = Math.max(1, distance * 0.000001);
        if (Math.abs(distance - request.distanceMeters()) > tolerance)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "GPS 경로와 제출 거리가 일치하지 않습니다. 기록을 보존하고 확인해주세요.");
    }

    // 검증된 경로에 정확도 50m 이하이면서 기준 정상 좌표에서 30m 이내인 표본이 하나라도 있으면 도달로 판정한다.
    public static boolean reachedSummit(HikingActivityRequest request, double lat, double lng) {
        for (var segment : request.gpsSegments()) {
            for (var point : segment) {
                if (point.accuracyMeters() <= 50 && distanceMeters(point.lat(), point.lng(), lat, lng) <= 30)
                    return true;
            }
        }
        return false;
    }

    // 도 단위 위도·경도를 라디안으로 바꿔 하버사인 공식과 지구 반지름 6,371,000m로 거리를 미터 단위 계산한다.
    public static double distanceMeters(double lat, double lng, double otherLat, double otherLng) {
        double h = Math.pow(Math.sin(Math.toRadians(otherLat - lat) / 2), 2)
            + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(otherLat))
            * Math.pow(Math.sin(Math.toRadians(otherLng - lng) / 2), 2);
        return EARTH_RADIUS_METERS * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    private static boolean finite(Double value) { return value != null && Double.isFinite(value); }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPS 증거의 형식, 개수 또는 시각을 확인해주세요.");
    }
}
