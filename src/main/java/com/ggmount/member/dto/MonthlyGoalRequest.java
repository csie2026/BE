package com.ggmount.member.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
/**
 * 선택한 월의 거리·등산 횟수 목표를 교체하며, 각 목표는 선택 사항이고 둘 다 null이면 설정을 삭제한다.
 * 값이 있으면 거리는 1~1000km에 소수점 이하 최대 세 자리, 횟수는 1~1000회로 검증한다.
 */
public record MonthlyGoalRequest(
    @DecimalMin("1") @DecimalMax("1000") @Digits(integer=4,fraction=3) BigDecimal distanceKm,
    @Min(1) @Max(1000) Integer hikeCount
) {}
