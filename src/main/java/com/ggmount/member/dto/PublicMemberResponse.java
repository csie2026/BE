package com.ggmount.member.dto;
import com.ggmount.member.domain.Member;
public record PublicMemberResponse(Long userId, String nickname, String profileImageUrl, Long score) {
    public static PublicMemberResponse from(Member m) {
        return new PublicMemberResponse(
            m.getId(),
            m.getNickname(),
            m.getProfileImageUrl(),
            m.getScore()
        );
    }
}
