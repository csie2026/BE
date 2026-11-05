package com.ggmount.hiking.domain;

import com.ggmount.member.domain.Member;
import com.ggmount.hiking.dto.HikingActivityRequest;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Actual completed GPS session; HikingRecord remains the existing journal entity. */
@Entity
@Table(name = "hiking_activities")
@Getter
public class HikingActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;
    @Column(nullable = false) private Long mountainId;
    private Long courseId;
    @Column(nullable = false, length = 100) private String mountainName;
    @Column(nullable = false) private Instant startedAt;
    @Column(nullable = false) private Instant endedAt;
    @Column(nullable = false) private double distanceMeters;
    @Column(nullable = false) private long elapsedMs;
    @Column(nullable = false) private boolean completed;
    @Column(nullable = false, length = 36) private String clientRequestId;
    private Short gpsValidationVersion;
    private Short requestFingerprintVersion;
    @Column(length = 64) private String requestFingerprint;

    @Column(nullable = false) private boolean gpsRecorded;
    @Column(length = 64) private String gpsEvidenceHash;

    protected HikingActivity() {}
    public HikingActivity(Member member, String mountainName, HikingActivityRequest r,
        boolean serverCompleted, String fingerprint) {
        this.member = member;
        this.mountainName = mountainName;
        mountainId = r.mountainId();
        courseId = r.courseId();
        startedAt = r.startedAt();
        endedAt = r.endedAt();
        distanceMeters = r.distanceMeters();
        elapsedMs = r.elapsedMs();
        completed = serverCompleted;
        clientRequestId = r.clientRequestId();
        gpsRecorded = com.ggmount.hiking.service.GpsEvidence.hasUsablePoint(r);
        gpsEvidenceHash = evidenceHash(r);
        gpsValidationVersion = com.ggmount.hiking.service.GpsEvidence.VALIDATION_VERSION;
        requestFingerprintVersion = com.ggmount.hiking.service.HikingRequestFingerprint.VERSION;
        requestFingerprint = fingerprint;
    }
    // Only a canonical fingerprint is persisted: no precise GPS coordinates in the activity table.
    public static String evidenceHash(HikingActivityRequest request) {
        var e = request.gpsEvidence();
        if (e == null) return null;
        String canonical = e.latitude() + ":" + e.longitude() + ":" + e.accuracyMeters() + ":" + e.recordedAt();
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public LocalDate hikingDate() {
        return startedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    }
}
