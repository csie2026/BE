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

// 인증 회원의 산행 목록·상세 조회, 저장과 점수 재산정을 제공한다. 성공 응답은 기본 200이다.
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
    // DTO 검증 실패는 공통 처리기에서 400, 산행 저장 충돌은 전용 오류 코드가 포함된 409로 응답한다.
    @PostMapping
    public HikingActivityResponse create(@AuthenticationPrincipal OAuthPrincipal p,
        @Valid @RequestBody HikingActivityRequest r) {
        try {
            return service.create(p, r);
        } catch (DataIntegrityViolationException e) {
            // 서비스 프록시의 트랜잭션 롤백 후 제약 위반을 변환한다. 지정한 제약 외의 예외는 그대로 전파한다.
            // 산행 세션 중복과 요청 ID 중복을 서로 다른 409 충돌로 구분한다.
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
            // Hibernate에서 제약 이름을 얻지 못한 경우에만 PostgreSQL의 23505 상태와 세션 제약 이름을 확인한다.
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
