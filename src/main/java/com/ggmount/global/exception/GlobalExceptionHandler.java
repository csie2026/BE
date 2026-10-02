package com.ggmount.global.exception;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestControllerAdvice
public class GlobalExceptionHandler {
 @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
 public ResponseEntity<?> oversizedUpload() { return ResponseEntity.status(413).body(Map.of("error","image_too_large","message","이미지는 5MiB 이하로 올려주세요.")); }
 @ExceptionHandler(MethodArgumentNotValidException.class)
 public ResponseEntity<?> validation(MethodArgumentNotValidException e) { return ResponseEntity.badRequest().body(Map.of("error","validation_failed","message",e.getBindingResult().getAllErrors().getFirst().getDefaultMessage())); }
 @ExceptionHandler(HttpMessageNotReadableException.class)
 public ResponseEntity<?> invalidJson() { return ResponseEntity.badRequest().body(Map.of("error","invalid_request","message","입력 형식을 확인해주세요.")); }
 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("error","request_failed","message",e.getReason()==null ? "요청을 처리할 수 없습니다." : e.getReason())); }
}
