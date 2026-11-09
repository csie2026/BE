package com.ggmount.member.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** expectedUserId is an account-switch precondition, never the source of ownership. */
public record WithdrawalRequest(
    @NotNull @Positive Long expectedUserId,
    @NotNull @Pattern(regexp = "탈퇴", message = "탈퇴 확인 문구를 입력해주세요.") String confirmation
) {}
