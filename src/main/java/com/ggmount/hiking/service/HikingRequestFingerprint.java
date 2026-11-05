package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.HikingActivityRequest;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public final class HikingRequestFingerprint {
    public static final short VERSION = 1;
    private HikingRequestFingerprint() {}

    /** V1: typed binary fields, list lengths and exact sample instants; no raw JSON dependence. */
    public static String of(HikingActivityRequest request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                out.writeShort(VERSION);
                out.writeLong(request.mountainId());
                out.writeBoolean(request.courseId() != null);
                if (request.courseId() != null) out.writeLong(request.courseId());
                instant(out, request.startedAt());
                instant(out, request.endedAt());
                out.writeDouble(request.distanceMeters());
                out.writeLong(request.elapsedMs());
                out.writeBoolean(request.completed()); // Client expectation, not the calculated result.
                out.writeInt(request.gpsSegments().size());
                for (var segment : request.gpsSegments()) {
                    out.writeInt(segment.size());
                    for (var point : segment) {
                        out.writeDouble(point.lat());
                        out.writeDouble(point.lng());
                        instant(out, point.measuredAt());
                        out.writeDouble(point.accuracyMeters());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Unable to calculate request fingerprint", impossible);
        }
    }

    private static void instant(DataOutputStream out, Instant instant) throws IOException {
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
    }
}
