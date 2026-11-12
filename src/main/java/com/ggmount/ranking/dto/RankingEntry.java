package com.ggmount.ranking.dto;

/**
 * 공개 랭킹 항목의 userId는 내부 회원 ID이며 null score는 미계산 상태로 보존한다.
 * rank는 서비스가 전체 참여자에 부여한 순번이다. null 점수는 뒤에 두고 동점은 회원 ID 오름차순으로 구분한다.
 */
public record RankingEntry(Long userId, String nickname, String profileImageUrl, Long score, long rank) {}
