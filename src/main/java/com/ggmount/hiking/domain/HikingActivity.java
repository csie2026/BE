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

    @Column(nullable = false) private boolean gpsRecorded;
    @Column(length = 64) private String gpsEvidenceHash;

    protected HikingActivity() {}
    public HikingActivity(Member member, String mountainName, HikingActivityRequest r) {
        this.member = member;
        this.mountainName = mountainName;
        mountainId = r.mountainId();
        courseId = r.courseId();
        startedAt = r.startedAt();
        endedAt = r.endedAt();
        distanceMeters = r.distanceMeters();
        elapsedMs = r.elapsedMs();
        completed = r.completed();
        clientRequestId = r.clientRequestId();
        gpsRecorded = r.gpsEvidence() != null;
        gpsEvidenceHash = evidenceHash(r);
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
