package com.ggmount.member.service;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.WithdrawalRequest;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WithdrawalService {
    private final MemberService members;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public WithdrawalService(MemberService members, JdbcTemplate jdbc, EntityManager entities) {
        this.members = members;
        this.jdbc = jdbc;
        this.entities = entities;
    }

    @Transactional
    public void withdraw(OAuthPrincipal principal, WithdrawalRequest request) {
        var member = members.current(principal);
        long id = member.getId();
        if (!Long.valueOf(id).equals(request.expectedUserId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "로그인 계정이 변경되었습니다. 다시 확인해주세요.");
        if (!"탈퇴".equals(request.confirmation()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구를 입력해주세요.");
        // Same lock as activity/journal/goal/image writers. Concurrent deletion never recreates an account.
        if (jdbc.queryForList("select id from members where id=? for update", Long.class, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        entities.flush();
        // Explicit dependency order: no anonymous copies or retained member-linked audit rows.
        for (String table : new String[]{"hiking_score_events", "hiking_daily_certifications",
            "hiking_activity_scores", "hiking_public_bonuses", "hiking_records", "hiking_activities",
            "member_personal_images", "member_monthly_goals"}) {
            jdbc.update("delete from " + table + " where member_id=?", id);
        }
        if (jdbc.update("delete from members where id=?", id) != 1)
            throw new IllegalStateException("회원탈퇴 처리를 완료하지 못했습니다.");
        // Uploaded image bytes live in member_personal_images.content (BYTEA), not external files.
        entities.clear();
    }
}
