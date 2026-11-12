package com.ggmount.hiking.domain;
import com.ggmount.member.domain.Member;
import com.ggmount.hiking.dto.HikingRecordRequest;
import com.ggmount.hiking.dto.JournalUpdateRequest;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.LocalDate;
/**
 * 산행 활동과 별도로 제목·내용·공개 여부를 저장하는 일지다.
 * JPA·DB 삭제 전파는 없으며 탈퇴 서비스가 일지→활동→회원 순서로 삭제한다.
 */
@Entity
@Table(name = "hiking_records")
@Getter
public class HikingRecord {
    // 활동 연결이 없는 기존 일지를 허용하는 선택적 지연 로딩 관계이며, 신규 생성은 서비스에서 본인 활동 연결을 요구한다.
    // 외래 키는 일지 테이블에 있으며 JPA 매핑과 DB 유일성 제약으로 같은 활동에 여러 일지가 연결되는 것을 제한한다.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hiking_activity_id", unique = true)
    private HikingActivity activity;
    // 일지 자체의 자동 생성 ID로, 연결된 산행 활동 ID 및 작성자의 내부 회원 ID와 구분한다.
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
    // 작성 시각이 아닌 산행 날짜이며, 생성 시 활동 시작 시각의 서울 날짜를 복사한다.
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
