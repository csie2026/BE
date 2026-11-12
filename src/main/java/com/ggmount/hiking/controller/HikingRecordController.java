package com.ggmount.hiking.controller;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.hiking.dto.*;
import com.ggmount.hiking.service.HikingRecordService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
// 공개 피드·본인 목록·상세·작성·수정·삭제를 연결하고, 공개 여부와 소유권 정책은 서비스가 판단한다.
// 산행 활동과 별도인 일지 자원을 다루며, 공개 조회 경로도 보안 설정에 따라 인증이 필요하다.
@RestController
@RequestMapping("/api")
public class HikingRecordController {
    private final HikingRecordService service;
    public HikingRecordController(HikingRecordService service) {
        this.service = service;
    }
    @GetMapping("/journals")
    public List<HikingRecordResponse> feed() {
        return service.publicRecords(null);
    }
    @GetMapping("/journals/{id}")
    public HikingRecordResponse detail(
        @AuthenticationPrincipal OAuthPrincipal p,
        @PathVariable Long id
    ) {
        return service.detail(p, id);
    }
    @GetMapping("/users/me/journals")
    public List<HikingRecordResponse> mine(@AuthenticationPrincipal OAuthPrincipal p) {
        return service.mine(p);
    }
    @GetMapping("/users/{id}/journals")
    public List<HikingRecordResponse> user(@PathVariable Long id) {
        return service.publicRecords(id);
    }
    // 생성·수정 요청은 DTO 검증 후 서비스로 전달한다. 검증 실패는 공통 처리기에서 400으로 응답한다.
    @PostMapping("/journals")
    public HikingRecordResponse create(
        @AuthenticationPrincipal OAuthPrincipal p,
        @Valid @RequestBody HikingRecordRequest r
    ) {
        return service.create(p, r);
    }
    @PatchMapping("/journals/{id}")
    public HikingRecordResponse update(
        @AuthenticationPrincipal OAuthPrincipal p,
        @PathVariable Long id,
        @Valid @RequestBody JournalUpdateRequest r
    ) {
        return service.update(p, id, r);
    }
    // 서비스에서 작성자 확인 후 일지를 삭제하며, 정상 완료 시 본문 없이 204를 반환한다.
    @DeleteMapping("/journals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @AuthenticationPrincipal OAuthPrincipal p,
        @PathVariable Long id
    ) {
        service.delete(p, id);
    }
}
