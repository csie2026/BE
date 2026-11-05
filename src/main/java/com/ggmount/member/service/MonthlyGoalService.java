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
    private MonthlyGoalResponse find(long memberId,YearMonth month) {
        return jdbc.query("select target_distance_km,target_hiking_count from member_monthly_goals where member_id=? and goal_month=?",
            (rs,i)->new MonthlyGoalResponse(memberId,month.toString(),rs.getBigDecimal(1),rs.getObject(2,Integer.class)),
            memberId,Date.valueOf(month.atDay(1))).stream().findFirst()
            .orElse(new MonthlyGoalResponse(memberId,month.toString(),null,null));
    }
    public MonthlyGoalResponse get(OAuthPrincipal principal,String selectedMonth) {
        var member=members.current(principal);members.requireComplete(member);
        return find(member.getId(),month(selectedMonth));
    }
    @Transactional
    public MonthlyGoalResponse save(OAuthPrincipal principal,String selectedMonth,MonthlyGoalRequest request) {
        var member=members.current(principal);members.requireComplete(member);
        YearMonth month=month(selectedMonth);
        personal.lockMember(member.getId());
        Date date=Date.valueOf(month.atDay(1));
        if(request.distanceKm()==null && request.hikeCount()==null) {
            jdbc.update("delete from member_monthly_goals where member_id=? and goal_month=?",member.getId(),date);
        } else {
            int changed=jdbc.update("update member_monthly_goals set target_distance_km=?,target_hiking_count=? where member_id=? and goal_month=?",
                request.distanceKm(),request.hikeCount(),member.getId(),date);
            if(changed==0) jdbc.update("insert into member_monthly_goals(member_id,goal_month,target_distance_km,target_hiking_count) values(?,?,?,?)",
                member.getId(),date,request.distanceKm(),request.hikeCount());
        }
        // Only settings change: GPS activity, journal and member score tables are never written.
        return find(member.getId(),month);
    }
}
