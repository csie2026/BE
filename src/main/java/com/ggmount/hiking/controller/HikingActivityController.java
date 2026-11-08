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
import org.postgresql.util.PSQLException;
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
    @PostMapping("/recalculate-score")
    public ScoreSummaryResponse recalculate(@AuthenticationPrincipal OAuthPrincipal p) {
        return service.recalculate(p);
    }
    @PostMapping
    public HikingActivityResponse create(@AuthenticationPrincipal OAuthPrincipal p,
        @Valid @RequestBody HikingActivityRequest r) {
        try {
            return service.create(p, r);
        } catch (DataIntegrityViolationException e) {
            // The proxied service transaction has rolled back before translating the error.
            boolean hasHibernateConstraintName = false;
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint) {
                    String name = constraint.getConstraintName();
                    hasHibernateConstraintName |= name != null;
                    if ("hiking_activity_session_unique".equals(name))
                        throw HikingActivityConflict.duplicate();
                    if ("hiking_activities_member_id_client_request_id_key".equals(name))
                        throw HikingActivityConflict.request();
                }
            }
            if (!hasHibernateConstraintName) {
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof PSQLException postgres
                        && "23505".equals(postgres.getSQLState())
                        && postgres.getServerErrorMessage() != null
                        && "hiking_activity_session_unique".equals(postgres.getServerErrorMessage().getConstraint()))
                        throw HikingActivityConflict.duplicate();
                }
            }
            throw e;
        }
    }
}
