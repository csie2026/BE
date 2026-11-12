package com.ggmount.member.dto;
import jakarta.validation.constraints.*;
import java.time.Year;
public record MemberUpdateRequest(
    @NotNull @Positive Long expectedUserId,
    @NotBlank @Size(max = 30) String nickname,
    @NotNull Integer birthYear
) {
    // 고정된 상한 대신 검증 시점의 현재 연도를 기준으로 출생연도 범위를 검사한다.
    @AssertTrue(message = "출생연도는 1900년부터 현재 연도 사이여야 합니다.")
    public boolean isBirthYearValid() {
        return birthYear != null && birthYear >= 1900 && birthYear <= Year.now()
            .getValue();
    }
}
