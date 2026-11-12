package com.ggmount.global.exception;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.util.Map;
/**
 * 검증 실패・잘못된 JSON・업로드 제한을 FE 공통 오류 처리에서 읽을 수 있는 응답으로 변환한다.
 * 산행 저장 충돌은 전용 오류 코드를 유지하고, 일반 상태 지정 예외는 지정된 응답 상태를 유지한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(com.ggmount.member.service.ProfileAccountMismatch.class)
    public ResponseEntity<?> profileAccountMismatch(com.ggmount.member.service.ProfileAccountMismatch e) {
        return ResponseEntity.status(409).cacheControl(org.springframework.http.CacheControl.noStore())
            .body(Map.of("error", "profile_account_mismatch", "message", e.getReason()));
    }
    /** 산행 저장 충돌의 종류를 구분할 수 있도록 전용 오류 코드와 메시지를 409 응답으로 전달한다. */
    @ExceptionHandler(com.ggmount.hiking.service.HikingActivityConflict.class)
    public ResponseEntity<?> hikingConflict(com.ggmount.hiking.service.HikingActivityConflict e) {
        return ResponseEntity.status(409).body(Map.of("error", e.code(), "message", e.getReason()));
    }
    /** 파일 업로드 크기 제한 초과를 이미지 용량 안내가 포함된 413 응답으로 변환한다. */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> oversizedUpload() {
        return ResponseEntity.status(413)
            .body(Map.of(
                "error",
                "image_too_large",
                "message",
                "이미지는 5MiB 이하로 올려주세요."
            ));
    }
    /** 요청 객체 검증 실패 시 첫 번째 검증 오류의 기본 메시지를 400 응답으로 전달한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest()
            .body(Map.of(
                "error",
                "validation_failed",
                "message",
                e.getBindingResult().getAllErrors().getFirst().getDefaultMessage()
            ));
    }
    /** 요청 본문을 읽거나 변환하지 못한 경우 상세 파싱 오류 대신 입력 형식 안내를 반환한다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidJson() {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "invalid_request", "message", "입력 형식을 확인해주세요."));
    }
    /** 예외에 지정된 응답 상태와 사유를 전달하며, 사유가 없으면 기본 안내 메시지를 사용한다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of(
                "error",
                "request_failed",
                "message",
                e.getReason() == null ? "요청을 처리할 수 없습니다." : e.getReason()
            ));
    }
}
