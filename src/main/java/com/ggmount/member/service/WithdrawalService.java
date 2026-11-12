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

    // 관련 데이터와 회원 삭제를 하나의 트랜잭션으로 묶어 런타임 예외 발생 시 함께 롤백한다.
    @Transactional
    public void withdraw(OAuthPrincipal principal, WithdrawalRequest request) {
        // 세션의 내부 회원 ID와 공급자·공급자 사용자 ID가 일치하는 회원을 확인하며, 확인 실패는 401이다.
        var member = members.current(principal);
        long id = member.getId();
        // 확인 화면의 회원과 현재 로그인 회원이 다르면 409, 확인 문구가 정확히 '탈퇴'가 아니면 400으로 거부한다.
        if (!Long.valueOf(id).equals(request.expectedUserId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "로그인 계정이 변경되었습니다. 다시 확인해주세요.");
        if (!"탈퇴".equals(request.confirmation()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구를 입력해주세요.");
        // 회원 행에 쓰기 잠금을 얻어 삭제를 진행하며, 이미 삭제되어 행이 없으면 401로 거부한다.
        if (jdbc.queryForList("select id from members where id=? for update", Long.class, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        // 영속성 컨텍스트의 변경을 먼저 반영한 뒤 같은 트랜잭션에서 직접 삭제 쿼리를 실행한다.
        entities.flush();
        // 아래 배열 순서대로 해당 회원의 점수·인증·일지·활동·이미지·월간 목표 데이터를 삭제한 뒤 회원을 삭제한다.
        for (String table : new String[]{"hiking_score_events", "hiking_daily_certifications",
            "hiking_activity_scores", "hiking_public_bonuses", "hiking_records", "hiking_activities",
            "member_personal_images", "member_monthly_goals"}) {
            jdbc.update("delete from " + table + " where member_id=?", id);
        }
        // 회원 삭제가 정확히 한 행에 적용되지 않으면 예외를 발생시켜 앞선 관련 데이터 삭제도 롤백한다.
        if (jdbc.update("delete from members where id=?", id) != 1)
            throw new IllegalStateException("회원탈퇴 처리를 완료하지 못했습니다.");
        // 업로드 이미지 바이트는 외부 파일이 아닌 위에서 삭제한 이미지 테이블의 content 열에 저장된다.
        // 직접 삭제한 회원이 영속성 컨텍스트에 남지 않도록 관리 중인 엔티티를 모두 분리한다.
        entities.clear();
    }
}
