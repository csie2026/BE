package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.HikingActivityRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

public final class GpsEvidence {
    // Persisted V1 evidence/fingerprint format remains unchanged. New inserts also check distance.
    public static final short VALIDATION_VERSION = 1;
    public static final int MAX_POINTS = 10_000;
    public static final int MAX_SEGMENTS = 10_000;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private GpsEvidence() {}

    /** Validate against raw activity instants before storage rounding. Never echo evidence. */
    public static void validate(HikingActivityRequest request) {
        var segments = request.gpsSegments();
        if (segments == null || segments.size() > MAX_SEGMENTS) throw invalid();
        int count = 0;
        Instant previous = request.startedAt();
        for (var segment : segments) {
            if (segment == null || segment.size() > MAX_POINTS - count) throw invalid();
            count += segment.size();
            for (var point : segment) {
                if (point == null || !finite(point.lat()) || Math.abs(point.lat()) > 90
                    || !finite(point.lng()) || Math.abs(point.lng()) > 180
                    || !finite(point.accuracyMeters()) || point.accuracyMeters() < 0
                    || point.measuredAt() == null || point.measuredAt().isBefore(previous)
                    || point.measuredAt().isAfter(request.endedAt())) throw invalid();
                previous = point.measuredAt();
            }
        }
    }

    /** Scoring eligibility comes from the validated route, never the optional client summary. */
    public static boolean hasUsablePoint(HikingActivityRequest request) {
        if (request.gpsSegments() == null) return false;
        return request.gpsSegments().stream().flatMap(java.util.Collection::stream)
            .anyMatch(point -> point.accuracyMeters() <= 50);
    }

    /** Same unrounded coordinates and per-segment Haversine sum as FE trackDistance.
     * Sparse/low-quality evidence remains saveable, but cannot certify a visit. */
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

    public static void validateDistance(HikingActivityRequest request) {
        if (!hasDistanceEvidence(request)) return;
        double distance = routeDistance(request);
        double tolerance = Math.max(1, distance * 0.000001);
        if (Math.abs(distance - request.distanceMeters()) > tolerance)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "GPS 경로와 제출 거리가 일치하지 않습니다. 기록을 보존하고 확인해주세요.");
    }

    public static boolean reachedSummit(HikingActivityRequest request, double lat, double lng) {
        for (var segment : request.gpsSegments()) {
            for (var point : segment) {
                if (point.accuracyMeters() <= 50 && distanceMeters(point.lat(), point.lng(), lat, lng) <= 30)
                    return true;
            }
        }
        return false;
    }

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
