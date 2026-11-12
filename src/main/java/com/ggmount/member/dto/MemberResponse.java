package com.ggmount.member.dto;

import com.ggmount.member.domain.Member;
import java.util.Map;
import java.time.Instant;

/**
 * 이메일·출생연도를 포함하는 본인 응답이다. userId는 외부 OAuth 식별자와 구분되는 내부 회원 ID다.
 */
public record MemberResponse(
    Long userId,
    String nickname,
    Integer birthYear,
    Integer age,
    String profileImageUrl,
    boolean profileCompleted,
    Long score,
    String backgroundImageUrl,
    String provider,
    String email,
    Instant joinedAt
) {
    public static MemberResponse from(Member member) {
        return withImages(member, Map.of());
    }

    // 본인 응답은 업로드 이미지의 revision URL을 우선하고, 없으면 OAuth 프로필 이미지를 사용한다.
    public static MemberResponse withImages(Member member, Map<String, String> revisions) {
        String profile = imageUrl("profile", revisions.get("profile"));
        return new MemberResponse(
            member.getId(),
            member.getNickname(),
            member.getBirthYear(),
            member.age(),
            profile == null ? member.getProfileImageUrl() : profile,
            member.isProfileCompleted(),
            member.getScore(),
            imageUrl(
                "background",
                revisions.get("background")
            ),
            member.getProvider(),
            member.getEmail(),
            member.getJoinedAt()
        );
    }

    private static String imageUrl(String kind, String revision) {
        return revision == null ? null : "/api/users/me/images/" + kind + "?v=" + revision;
    }
}
