package com.ggmount.member.controller;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.*;
import com.ggmount.member.service.MonthlyGoalService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/monthly-goals")
public class MonthlyGoalController {
    private final MonthlyGoalService service;
    public MonthlyGoalController(MonthlyGoalService service) { this.service=service; }
    @GetMapping("/{month}")
    public ResponseEntity<MonthlyGoalResponse> get(@AuthenticationPrincipal OAuthPrincipal principal,@PathVariable String month) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(principal,month));
    }
    @PutMapping("/{month}")
    public ResponseEntity<MonthlyGoalResponse> save(@AuthenticationPrincipal OAuthPrincipal principal,@PathVariable String month,
        @Valid @RequestBody MonthlyGoalRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(principal,month,request));
    }
}
