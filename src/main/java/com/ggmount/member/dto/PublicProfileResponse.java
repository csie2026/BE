package com.ggmount.member.dto;

public record PublicProfileResponse(
    Long userId, String nickname, String profileImageUrl, Long score,
    long totalHikeCount, long completedHikeCount
) {
    public static PublicProfileResponse from(PublicMemberResponse member, long totalHikes, long completedHikes) {
        return new PublicProfileResponse(member.userId(), member.nickname(), member.profileImageUrl(),
            member.score(), totalHikes, completedHikes);
    }
}
