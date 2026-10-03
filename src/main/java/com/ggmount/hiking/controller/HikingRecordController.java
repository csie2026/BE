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
    @PostMapping("/journals")
    public HikingRecordResponse create(
        @AuthenticationPrincipal OAuthPrincipal p,
        @Valid @RequestBody HikingRecordRequest r
    ) {
        return service.save(p, null, r);
    }
    @PatchMapping("/journals/{id}")
    public HikingRecordResponse update(
        @AuthenticationPrincipal OAuthPrincipal p,
        @PathVariable Long id,
        @Valid @RequestBody HikingRecordRequest r
    ) {
        return service.save(p, id, r);
    }
    @DeleteMapping("/journals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @AuthenticationPrincipal OAuthPrincipal p,
        @PathVariable Long id
    ) {
        service.delete(p, id);
    }
}
