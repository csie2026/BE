package com.ggmount.hiking.domain;
import com.ggmount.member.domain.Member;
import com.ggmount.hiking.dto.HikingRecordRequest;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.LocalDate;
@Entity @Table(name="hiking_records") @Getter
public class HikingRecord {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="member_id",nullable=false) private Member member;
 @Column(nullable=false,length=100) private String mountainName;
 @Column(nullable=false,length=100) private String title;
 @Column(nullable=false,length=10000) private String content;
 @Column(nullable=false) private LocalDate hikingDate;
 @Column(name="is_public",nullable=false) private boolean publicRecord;
 protected HikingRecord() {}
 public HikingRecord(Member member,HikingRecordRequest r) { this.member=member; update(r); }
 public void update(HikingRecordRequest r) { mountainName=r.mountainName().trim(); title=r.title().trim(); content=r.content(); hikingDate=r.hikingDate(); publicRecord=r.isPublic(); }
}
