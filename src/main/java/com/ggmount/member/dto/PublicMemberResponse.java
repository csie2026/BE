package com.ggmount.member.dto;
import com.ggmount.member.domain.Member;
// 타인 프로필과 랭킹에는 이메일·출생연도·개인 배경 이미지 없이 공개 필드만 전달한다.
// userId는 외부 OAuth 식별자가 아닌 내부 회원 ID다.
public record PublicMemberResponse(Long userId, String nickname, String profileImageUrl, Long score) {
    public static PublicMemberResponse from(Member m) {
        return withImage(m, null);
    }
    public static PublicMemberResponse withImage(Member m, String revision) {
        return new PublicMemberResponse(
            m.getId(),
            m.getNickname(),
            ProfileImageUrls.publicProfile(m.getId(), revision, m.getProfileImageUrl()),
            m.getScore()
        );
    }
}
