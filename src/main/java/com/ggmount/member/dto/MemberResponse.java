package com.ggmount.member.dto;

import com.ggmount.member.domain.Member;
import java.util.Map;

public record MemberResponse(Long userId, String nickname, Integer birthYear, Integer age,
        String profileImageUrl, boolean profileCompleted, Long score, String backgroundImageUrl) {
    public static MemberResponse from(Member member) {
        return withImages(member, Map.of());
    }

    public static MemberResponse withImages(Member member, Map<String, String> revisions) {
        String profile = imageUrl("profile", revisions.get("profile"));
        return new MemberResponse(member.getId(), member.getNickname(), member.getBirthYear(), member.age(),
                profile == null ? member.getProfileImageUrl() : profile,
                member.isProfileCompleted(), member.getScore(), imageUrl("background", revisions.get("background")));
    }

    private static String imageUrl(String kind, String revision) {
        return revision == null ? null : "/api/users/me/images/" + kind + "?v=" + revision;
    }
}
