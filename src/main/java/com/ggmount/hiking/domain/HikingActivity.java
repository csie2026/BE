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
    }
    public LocalDate hikingDate() {
        return startedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    }
}
