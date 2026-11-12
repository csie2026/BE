package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.GpsPointRequest;
import com.ggmount.hiking.dto.HikingActivityRequest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// 합성 좌표로 경로 거리·오차·증거 조건을 검사하며 실제 이동이나 기기 위치의 진위를 입증하지 않는다.
class GpsEvidenceTests {
    static final Instant START = Instant.parse("2020-01-01T00:00:00Z");
    static GpsPointRequest point(double lat, double lng, int seconds) {
        return new GpsPointRequest(lat, lng, START.plusSeconds(seconds), 5.0);
    }
    static HikingActivityRequest request(double distance, List<List<GpsPointRequest>> segments) {
        return new HikingActivityRequest(1L, null, START, START.plusSeconds(20000), distance,
            1200000L, false, UUID.randomUUID().toString(), null, segments);
    }
    @Test void tenThousandUnroundedPointsMatchFrontendGoldenDistance() {
        // 고정된 기대 거리와 비교하며 실행 중 프런트엔드 계산 코드를 호출하지는 않는다.
        var points = new ArrayList<GpsPointRequest>();
        for (int i = 0; i < 10000; i++) points.add(point(37 + i * 0.00001, 127 + (i % 2) * 0.00001, i));
        var r = request(14225.35696292904, List.of(List.of(), points));
        GpsEvidence.validate(r);
        assertEquals(r.distanceMeters(), GpsEvidence.routeDistance(r), 0.000001);
        assertDoesNotThrow(() -> GpsEvidence.validateDistance(r));
    }
    @Test void gapsAndPausesNeverConnectSegmentsAndToleranceDoesNotRewriteSummary() {
        // 분리 구간의 합산 결과를 기준으로 오차를 주므로, 구간 사이 거리를 제외했는지 독립 기대값으로 직접 단언하지는 않는다.
        var segments = List.of(List.of(point(37,127,1),point(37.01,127,10)),
            List.<GpsPointRequest>of(), List.of(point(38,128,1000),point(38.01,128,1010)));
        double distance = GpsEvidence.routeDistance(request(0,segments));
        for (double delta : new double[]{0, 0.999, -0.999}) {
            var r = request(distance + delta,segments);
            assertDoesNotThrow(() -> GpsEvidence.validateDistance(r));
            assertEquals(distance + delta,r.distanceMeters());
        }
        for (double delta : new double[]{1.001,-1.001,500})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> GpsEvidence.validateDistance(request(distance + delta,segments)));
    }
    @Test void emptySingletonStationaryAndInaccurateEvidenceCannotCertifyDistance() {
        // 형식상 유효한 자료라도 거리 증거가 부족하면 거리 대조를 생략한다. 이 사례가 실제 점수 인증까지 실행하는 것은 아니다.
        for (var segments : List.of(List.<List<GpsPointRequest>>of(),
            List.of(List.of(point(37,127,1))),
            List.of(List.of(point(37,127,1),point(37,127,100))),
            List.of(List.of(point(37,127,1),new GpsPointRequest(37.01,127.0,START.plusSeconds(100),51.0))))) {
            var r = request(5000,segments);
            GpsEvidence.validate(r);
            assertFalse(GpsEvidence.hasDistanceEvidence(r));
            assertDoesNotThrow(() -> GpsEvidence.validateDistance(r));
        }
    }
}
