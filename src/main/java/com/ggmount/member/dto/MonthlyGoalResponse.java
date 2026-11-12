package com.ggmount.member.dto;
import java.math.BigDecimal;
// 내부 회원 ID와 YYYY-MM 형식의 대상 월, 거리(km)·등산 횟수 목표를 반환하며 미설정 목표는 null이다.
public record MonthlyGoalResponse(long userId, String month, BigDecimal distanceKm, Integer hikeCount) {}
