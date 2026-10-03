package com.ggmount.member.dto;
import jakarta.validation.constraints.*;
import java.time.Year;
public record MemberUpdateRequest(@NotBlank @Size(max = 30) String nickname, @NotNull Integer birthYear) {
    @AssertTrue(message = "출생연도는 1900년부터 현재 연도 사이여야 합니다.")
    public boolean isBirthYearValid() {
        return birthYear != null && birthYear >= 1900 && birthYear <= Year.now()
            .getValue();
    }
}
