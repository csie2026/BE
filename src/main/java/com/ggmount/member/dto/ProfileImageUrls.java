package com.ggmount.member.dto;

/** The same stored profile revision is served privately to its owner and by ID to public readers. */
public final class ProfileImageUrls {
    private ProfileImageUrls() {}
    public static String publicProfile(long memberId, String revision, String oauthFallback) {
        return revision == null ? oauthFallback : "/api/users/" + memberId + "/images/profile?v=" + revision;
    }
}
