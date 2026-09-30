package com.ggmount.member.domain;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Year;
@Entity @Table(name="members", uniqueConstraints=@UniqueConstraint(columnNames={"provider","provider_id"})) @Getter
public class Member {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private String provider;
 @Column(name="provider_id",nullable=false) private String providerId;
 private String email;
 @Column(length=30) private String nickname;
 private Integer birthYear;
 @Column(length=2048) private String profileImageUrl;
 @Column(nullable=false) private boolean profileCompleted;
 // Null means no ranking score has been calculated yet.
 private Long score;
 protected Member() {}
 public Member(OAuthUserInfo info) { provider=info.provider(); providerId=info.providerId(); nickname=info.nickname()==null ? null : info.nickname().substring(0,Math.min(30,info.nickname().length())); refreshOAuth(info); }
 public void refreshOAuth(OAuthUserInfo info) { email=info.email(); profileImageUrl=info.profileImage(); }
 public void completeProfile(String nickname, Integer birthYear) { this.nickname=nickname.trim(); this.birthYear=birthYear; profileCompleted=true; }
 public Integer age() { return birthYear==null ? null : Year.now().getValue()-birthYear+1; }
}
