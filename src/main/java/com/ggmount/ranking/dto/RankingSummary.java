package com.ggmount.ranking.dto;

import java.util.List;
public record RankingSummary(List<RankingEntry> members, RankingEntry mine, long totalMembers) {}
