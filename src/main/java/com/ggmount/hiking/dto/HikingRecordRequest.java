package com.ggmount.hiking.dto;
import jakarta.validation.constraints.*;
/**
 * 본인 산행 활동에 연결할 일지 생성 요청이다. 활동 ID와 기존 일지 유무는 서비스에서 확인한다.
 */
public record HikingRecordRequest(
    // 일지 ID가 아니라 연결할 산행 활동의 ID이며 양수여야 한다.
    @NotNull @Positive Long hikingRecordId,
    @NotBlank @Size(max = 100) String title,
    // 빈 내용은 허용하지만 null은 허용하지 않는다.
    @NotNull @Size(max = 10000) String content,
    @NotNull Boolean isPublic
) {
}
