package com.ggmount.member.controller;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.*;
import com.ggmount.member.service.MonthlyGoalService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 월간 목표의 소유자는 요청의 회원 ID가 아니라 인증 사용자 정보로 확인한 본인 회원이다.
@RestController
@RequestMapping("/api/users/me/monthly-goals")
public class MonthlyGoalController {
    private final MonthlyGoalService service;
    public MonthlyGoalController(MonthlyGoalService service) { this.service=service; }
    // 선택한 월의 목표를 조회하며 조회·저장 응답 모두 캐시 저장을 금지한다.
    @GetMapping("/{month}")
    public ResponseEntity<MonthlyGoalResponse> get(@AuthenticationPrincipal OAuthPrincipal principal,@PathVariable String month) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(principal,month));
    }
    // 요청 값 검증을 거쳐 해당 월의 목표 전체를 교체하고, 두 목표가 모두 없으면 설정을 삭제한다.
    @PutMapping("/{month}")
    public ResponseEntity<MonthlyGoalResponse> save(@AuthenticationPrincipal OAuthPrincipal principal,@PathVariable String month,
        @Valid @RequestBody MonthlyGoalRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(principal,month,request));
    }
}
