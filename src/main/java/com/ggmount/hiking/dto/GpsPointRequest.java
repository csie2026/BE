package com.ggmount.hiking.dto;

import java.time.Instant;

/** Submitted evidence, not an attestation of a device's physical location. */
public record GpsPointRequest(Double lat, Double lng, Instant measuredAt, Double accuracyMeters) {
    @Override public String toString() { return "GpsPointRequest[redacted]"; }
}
