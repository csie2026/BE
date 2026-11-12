package com.ggmount.member.dto;

/** 본인 전용 조회와 동일한 저장 이미지 버전을 가리키는 공개 프로필 이미지 URL을 구성한다. */
public final class ProfileImageUrls {
    private ProfileImageUrls() {}
    // 버전이 없으면 공급자 이미지 주소를 그대로 반환하고, 있으면 내부 회원 ID와 v를 조합한다.
    public static String publicProfile(long memberId, String revision, String oauthFallback) {
        return revision == null ? oauthFallback : "/api/users/" + memberId + "/images/profile?v=" + revision;
    }
}
