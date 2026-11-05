package com.ggmount.hiking.dto;
public record ScoreSummaryResponse(long totalPoints, long visitPoints, long summitPoints,
    long publicBonusPoints, String policyVersion) {}
