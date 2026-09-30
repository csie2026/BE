package com.ggmount.member.dto;
import com.ggmount.member.domain.Member;
public record MemberResponse(Long userId,String nickname,Integer birthYear,Integer age,String profileImageUrl,boolean profileCompleted,Long score) {
 public static MemberResponse from(Member m) { return new MemberResponse(m.getId(),m.getNickname(),m.getBirthYear(),m.age(),m.getProfileImageUrl(),m.isProfileCompleted(),m.getScore()); }
}
