package com.ggmount.ranking.dto;

/** Server-assigned ordinal rank; null score is preserved as uncalculated. */
public record RankingEntry(Long userId, String nickname, String profileImageUrl, Long score, long rank) {}
