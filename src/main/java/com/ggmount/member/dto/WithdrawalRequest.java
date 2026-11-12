package com.ggmount.member.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 예상 내부 회원 ID는 계정 전환 여부를 확인하는 조건이며, 실제 탈퇴 대상은 현재 인증 회원으로 결정한다.
 * 예상 회원 ID는 필수 양수이고 확인 문구는 공백 추가 없이 정확히 '탈퇴'여야 한다.
 */
public record WithdrawalRequest(
    @NotNull @Positive Long expectedUserId,
    @NotNull @Pattern(regexp = "탈퇴", message = "탈퇴 확인 문구를 입력해주세요.") String confirmation
) {}
