package com.ggmount.member.dto;
import java.math.BigDecimal;
public record MonthlyGoalResponse(long userId, String month, BigDecimal distanceKm, Integer hikeCount) {}
