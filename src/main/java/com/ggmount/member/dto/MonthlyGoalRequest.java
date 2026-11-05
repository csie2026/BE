package com.ggmount.member.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
/** PUT replaces the selected month's targets. Both null clears that month's settings. */
public record MonthlyGoalRequest(
    @DecimalMin("1") @DecimalMax("1000") @Digits(integer=4,fraction=3) BigDecimal distanceKm,
    @Min(1) @Max(1000) Integer hikeCount
) {}
