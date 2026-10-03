package com.ggmount.member.controller;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.*;
import com.ggmount.member.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
// 본인 회원 응답과 타인 공개 프로필을 구분하며, 개인정보 노출 범위는 각각의 DTO로 제한한다.
@RestController
@RequestMapping("/api/users")
public class MemberController {
    private final MemberService service;
    public MemberController(MemberService service) {
        this.service = service;
    }
    @GetMapping("/me")
    public ResponseEntity<MemberResponse> me(@AuthenticationPrincipal OAuthPrincipal p) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.me(p));
    }
    @PatchMapping("/me/profile")
    public MemberResponse update(
        @AuthenticationPrincipal OAuthPrincipal p,
        @Valid @RequestBody MemberUpdateRequest request
    ) {
        return service.update(p, request);
    }
    @GetMapping("/{id}/profile")
    public PublicMemberResponse profile(@PathVariable Long id) {
        var m = service.find(id);
        service.requireComplete(m);
        return PublicMemberResponse.from(m);
    }
}
