package com.ggmount.ranking.dto;

import java.util.List;
/**
 * members는 상위 최대 100명, mine은 상위 목록과 무관한 본인의 전체 순위 항목이며 대상이 아니면 null이다.
 * totalMembers는 null 점수를 포함한 프로필 완료 회원 수다. 순위·인원은 100명 제한 전에 계산하며 참여자가 없으면 빈 목록·0을 반환한다.
 */
public record RankingSummary(List<RankingEntry> members, RankingEntry mine, long totalMembers) {}
