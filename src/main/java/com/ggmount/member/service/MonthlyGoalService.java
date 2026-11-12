package com.ggmount.member.service;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.*;
import com.ggmount.member.repository.PersonalDataRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.sql.Date;
import java.time.YearMonth;

@Service
@Transactional(readOnly=true)
public class MonthlyGoalService {
    private final MemberService members;
    private final PersonalDataRepository personal;
    private final JdbcTemplate jdbc;
    public MonthlyGoalService(MemberService members, PersonalDataRepository personal, JdbcTemplate jdbc) {
        this.members=members;this.personal=personal;this.jdbc=jdbc;
    }
    // 네 자리 연도와 두 자리 월의 YYYY-MM 형식 및 1900년 이상 조건을 검사한다.
    // 이 구간의 형식·날짜 변환 실패는 400 응답으로 변환한다.
    private YearMonth month(String value) {
        try {
            if(value==null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();
            YearMonth month=YearMonth.parse(value);
            if(month.getYear()<1900) throw new IllegalArgumentException();
            return month;
        } catch(RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"목표 월을 YYYY-MM 형식으로 입력해주세요.");
        }
    }
    // 내부 회원 ID와 해당 월의 1일로 조회하며, 저장된 행이 없으면 두 목표를 null로 응답한다.
    private MonthlyGoalResponse find(long memberId,YearMonth month) {
        return jdbc.query("select target_distance_km,target_hiking_count from member_monthly_goals where member_id=? and goal_month=?",
            (rs,i)->new MonthlyGoalResponse(memberId,month.toString(),rs.getBigDecimal(1),rs.getObject(2,Integer.class)),
            memberId,Date.valueOf(month.atDay(1))).stream().findFirst()
            .orElse(new MonthlyGoalResponse(memberId,month.toString(),null,null));
    }
    // 조회·저장 모두 내부 회원 ID와 공급자 정보를 대조해 본인을 확인하고 프로필 완료 여부를 검사한다.
    public MonthlyGoalResponse get(OAuthPrincipal principal,String selectedMonth) {
        var member=members.current(principal);members.requireComplete(member);
        return find(member.getId(),month(selectedMonth));
    }
    @Transactional
    public MonthlyGoalResponse save(OAuthPrincipal principal,String selectedMonth,MonthlyGoalRequest request) {
        var member=members.current(principal);members.requireComplete(member);
        YearMonth month=month(selectedMonth);
        // 쓰기 트랜잭션에서 회원 행을 잠가 같은 회원의 동시 최초 저장도 순차 처리한다.
        // 기존 행 갱신 후 없을 때만 삽입하며, 데이터베이스의 회원·월 복합 기본 키도 중복 행을 제한한다.
        personal.lockMember(member.getId());
        Date date=Date.valueOf(month.atDay(1));
        // 두 목표가 모두 없으면 행을 삭제하고, 하나만 없으면 해당 목표를 null로 저장한다.
        if(request.distanceKm()==null && request.hikeCount()==null) {
            jdbc.update("delete from member_monthly_goals where member_id=? and goal_month=?",member.getId(),date);
        } else {
            int changed=jdbc.update("update member_monthly_goals set target_distance_km=?,target_hiking_count=? where member_id=? and goal_month=?",
                request.distanceKm(),request.hikeCount(),member.getId(),date);
            if(changed==0) jdbc.update("insert into member_monthly_goals(member_id,goal_month,target_distance_km,target_hiking_count) values(?,?,?,?)",
                member.getId(),date,request.distanceKm(),request.hikeCount());
        }
        // 목표 설정만 변경하며 GPS 활동·일지·회원 점수 데이터는 수정하지 않는다.
        return find(member.getId(),month);
    }
}
