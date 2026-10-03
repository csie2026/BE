package com.ggmount.hiking.domain;
import com.ggmount.member.domain.Member;
import com.ggmount.hiking.dto.HikingRecordRequest;
import com.ggmount.hiking.dto.JournalUpdateRequest;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.LocalDate;
@Entity
@Table(name = "hiking_records")
@Getter
public class HikingRecord {
    // Historical name: this entity is a journal. Null activity is allowed only for legacy rows.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hiking_activity_id", unique = true)
    private HikingActivity activity;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;
    @Column(nullable = false, length = 100)
    private String mountainName;
    @Column(nullable = false, length = 100)
    private String title;
    @Column(nullable = false, length = 10000)
    private String content;
    @Column(nullable = false)
    private LocalDate hikingDate;
    @Column(name = "is_public", nullable = false)
    // 공개 피드와 타인 조회의 기준이며, 비공개여도 작성자는 본인 목록과 상세에서 조회할 수 있다.
    private boolean publicRecord;
    protected HikingRecord() {
    }
    public HikingRecord(Member member, HikingActivity activity, HikingRecordRequest r) {
        this.member = member;
        this.activity = activity;
        mountainName = activity.getMountainName();
        hikingDate = activity.hikingDate();
        updateContent(r.title(), r.content(), r.isPublic());
    }
    public void update(JournalUpdateRequest r) {
        updateContent(r.title(), r.content(), r.isPublic());
    }
    private void updateContent(String title, String content, boolean isPublic) {
        this.title = title.trim();
        this.content = content;
        publicRecord = isPublic;
    }
}
