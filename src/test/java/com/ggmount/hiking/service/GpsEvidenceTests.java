package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.GpsPointRequest;
import com.ggmount.hiking.dto.HikingActivityRequest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
        var points = new ArrayList<GpsPointRequest>();
        for (int i = 0; i < 10000; i++) points.add(point(37 + i * 0.00001, 127 + (i % 2) * 0.00001, i));
        var r = request(14225.35696292904, List.of(List.of(), points));
        GpsEvidence.validate(r);
        assertEquals(r.distanceMeters(), GpsEvidence.routeDistance(r), 0.000001);
        assertDoesNotThrow(() -> GpsEvidence.validateDistance(r));
    }
    @Test void gapsAndPausesNeverConnectSegmentsAndToleranceDoesNotRewriteSummary() {
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
