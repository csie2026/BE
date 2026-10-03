package com.ggmount.hiking.service;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.hiking.domain.HikingActivity;
import com.ggmount.hiking.dto.*;
import com.ggmount.hiking.repository.*;
import com.ggmount.member.service.MemberService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class HikingActivityService {
    private final HikingActivityRepository activities;
    private final HikingRecordRepository journals;
    private final MemberService members;
    private final JdbcTemplate jdbc;
    public HikingActivityService(HikingActivityRepository activities, HikingRecordRepository journals,
        MemberService members, JdbcTemplate jdbc) {
        this.activities = activities; this.journals = journals; this.members = members; this.jdbc = jdbc;
    }
    private HikingActivityResponse response(HikingActivity a) {
        return HikingActivityResponse.from(a, journals.findByActivityId(a.getId())
            .map(com.ggmount.hiking.domain.HikingRecord::getId).orElse(null));
    }
    public List<HikingActivityResponse> mine(OAuthPrincipal p) {
        return activities.findByMemberIdOrderByStartedAtDescIdDesc(members.current(p).getId())
            .stream().map(this::response).toList();
    }
    public HikingActivityResponse detail(OAuthPrincipal p, Long id) {
        return response(activities.findByIdAndMemberId(id, members.current(p).getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
    @Transactional
    public HikingActivityResponse create(OAuthPrincipal p, HikingActivityRequest r) {
        var member = members.current(p);
        members.requireComplete(member);
        // Serialize retries from the same account before checking the unique request key.
        jdbc.queryForObject("select id from members where id=? for update", Long.class, member.getId());
        var existing = activities.findByMemberIdAndClientRequestId(member.getId(), r.clientRequestId());
        if (existing.isPresent()) {
            var a = existing.get();
            if (!a.getMountainId().equals(r.mountainId()) || !Objects.equals(a.getCourseId(), r.courseId())
                || !a.getStartedAt().equals(r.startedAt()) || !a.getEndedAt().equals(r.endedAt())
                || Double.compare(a.getDistanceMeters(), r.distanceMeters()) != 0
                || a.getElapsedMs() != r.elapsedMs() || a.isCompleted() != r.completed())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 산행에 사용된 저장 요청 ID입니다.");
            return response(a);
        }
        String mountainName = jdbc.query("select name from mountains where id=?",
            (rs, i) -> rs.getString(1), r.mountainId()).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "산을 찾을 수 없습니다."));
        if (r.courseId() != null && jdbc.queryForObject(
            "select count(*) from courses where id=? and mountain_id=?", Long.class,
            r.courseId(), r.mountainId()) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "산과 코스가 일치하지 않습니다.");
        // Session metrics are client reported, not server-verified summit proof or ranking input.
        return response(activities.saveAndFlush(new HikingActivity(member, mountainName, r)));
    }
}
