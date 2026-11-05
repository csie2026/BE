package com.ggmount.hiking.controller;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.hiking.dto.*;
import com.ggmount.hiking.service.HikingActivityService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import com.ggmount.hiking.service.HikingActivityConflict;

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
        @Valid @RequestBody HikingActivityRequest r) {
        try {
            return service.create(p, r);
        } catch (DataIntegrityViolationException e) {
            // The proxied service transaction has rolled back before translating the error.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint) {
                    String name = constraint.getConstraintName();
                    if (name != null && name.contains("hiking_activity_session_unique"))
                        throw HikingActivityConflict.duplicate();
                    if (name != null && name.contains("hiking_activities_member_id_client_request_id_key"))
                        throw HikingActivityConflict.request();
                }
            }
            throw e;
        }
    }
}
