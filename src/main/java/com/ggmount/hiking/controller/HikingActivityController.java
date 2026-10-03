package com.ggmount.hiking.controller;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.hiking.dto.*;
import com.ggmount.hiking.service.HikingActivityService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users/me/hiking-records")
public class HikingActivityController {
    private final HikingActivityService service;
    public HikingActivityController(HikingActivityService service) { this.service = service; }
    @GetMapping
    public List<HikingActivityResponse> mine(@AuthenticationPrincipal OAuthPrincipal p) {
        return service.mine(p);
    }
    @GetMapping("/{id}")
    public HikingActivityResponse detail(@AuthenticationPrincipal OAuthPrincipal p, @PathVariable Long id) {
        return service.detail(p, id);
    }
    @PostMapping
    public HikingActivityResponse create(@AuthenticationPrincipal OAuthPrincipal p,
        @Valid @RequestBody HikingActivityRequest r) { return service.create(p, r); }
}
