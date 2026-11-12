package com.ggmount.hiking.domain;

import com.ggmount.member.domain.Member;
import com.ggmount.hiking.dto.HikingActivityRequest;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 저장된 산행 활동으로, 등산일지인 HikingRecord와 별도로 관리한다. */
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
        gpsRecorded = com.ggmount.hiking.service.GpsEvidence.hasDistanceEvidence(r);
        gpsEvidenceHash = evidenceHash(r);
        gpsValidationVersion = com.ggmount.hiking.service.GpsEvidence.VALIDATION_VERSION;
        requestFingerprintVersion = com.ggmount.hiking.service.HikingRequestFingerprint.VERSION;
        requestFingerprint = fingerprint;
    }
    // 선택적 GPS 표본은 SHA-256 해시로 저장하며, 활동 테이블에 정밀 좌표를 남기지 않는다.
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
