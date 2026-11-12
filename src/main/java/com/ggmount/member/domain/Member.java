package com.ggmount.member.domain;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Year;
import java.time.Instant;
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(
    name = "members",
    uniqueConstraints = @UniqueConstraint(columnNames = {
        "provider", "provider_id"
        }
    )
)
@Getter
public class Member {
    // 공급자 계정 식별 정보와 구분되는 내부 회원 ID이며, 데이터베이스에서 자동 생성한다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    // 내부 회원 ID와 별개인 OAuth 계정 식별자이며 providerId와 함께 유일성을 갖는다.
    private String provider;
    @Column(name = "provider_id", nullable = false)
    private String providerId;
    private String email;
    @Column(length = 30)
    private String nickname;
    private Integer birthYear;
    @Column(length = 2048)
    private String profileImageUrl;
    @Column(nullable = false)
    // 최초 OAuth 로그인만으로는 완료되지 않으며 사용자가 닉네임·출생연도를 저장해야 활성화된다.
    private boolean profileCompleted;
    // 순위 점수를 아직 산정하지 않은 회원은 점수가 null이다.
    private Long score;
    // 가입 시각 필드 도입 전 회원의 가입 시각은 알 수 없으며, 활동 날짜로 추정하지 않는다.
    @Column(name = "joined_at", updatable = false)
    private Instant joinedAt;

    @PrePersist
    void recordJoinTime() {
        if (joinedAt == null) joinedAt = Instant.now();
    }
    protected Member() {
    }
    public Member(OAuthUserInfo info) {
        provider = info.provider();
        providerId = info.providerId();
        nickname = info.nickname() == null
            ? null
            : info.nickname()
                .substring(0, Math.min(30, info.nickname().length()));
        refreshOAuth(info);
    }
    // 공급자에서 받은 이메일과 프로필 이미지만 갱신하고 사용자가 설정한 닉네임·출생연도는 유지한다.
    public void refreshOAuth(OAuthUserInfo info) {
        email = info.email();
        profileImageUrl = info.profileImage();
    }
    public void completeProfile(String nickname,
        Integer birthYear) {
        this.nickname = nickname.trim();
        this.birthYear = birthYear;
        profileCompleted = true;
    }
    public void replaceHikingScore(long points) {
        if (points < 0) throw new IllegalArgumentException("점수는 음수일 수 없습니다.");
        score = points;
    }
    // 출생연도가 있으면 현재 연도와의 차이에 1을 더한 나이를 반환한다.
    public Integer age() {
        return birthYear == null ? null : Year.now().getValue() - birthYear + 1;
    }
}
