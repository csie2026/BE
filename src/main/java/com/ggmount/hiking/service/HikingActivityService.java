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
        var raw = r;
        r = r.normalizedForStorage();
        // Serialize retries from the same account before checking the unique request key.
        jdbc.queryForObject("select id from members where id=? for update", Long.class, member.getId());
        var existing = activities.findAllByMemberIdAndClientRequestIdIgnoreCase(member.getId(), r.clientRequestId());
        // Legacy case variants must be resolved explicitly, never picked or merged silently.
        if (existing.size() > 1)
            throw HikingActivityConflict.request();
        if (!existing.isEmpty()) {
            var a = existing.getFirst();
            if (a.getGpsValidationVersion() != null) {
                if (raw.gpsSegments() == null) throw HikingActivityConflict.request();
                GpsEvidence.validate(raw);
                if (!Objects.equals(a.getRequestFingerprintVersion(), HikingRequestFingerprint.VERSION)
                    || !Objects.equals(a.getRequestFingerprint(), HikingRequestFingerprint.of(r)))
                    throw HikingActivityConflict.request();
                return response(a);
            }
            if (!a.getMountainId().equals(r.mountainId()) || !Objects.equals(a.getCourseId(), r.courseId())
                || !a.getStartedAt().equals(r.startedAt()) || !a.getEndedAt().equals(r.endedAt())
                || Double.compare(a.getDistanceMeters(), r.distanceMeters()) != 0
                || a.getElapsedMs() != r.elapsedMs() || a.isCompleted() != r.completed())
                throw HikingActivityConflict.request();
            return response(a);
        }
        GpsEvidence.validate(raw);
        var mountain = jdbc.query("select name,lat,lng from mountains where id=?",
            (rs, i) -> new Summit(rs.getString(1), rs.getDouble(2), rs.getDouble(3)), r.mountainId()).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "산을 찾을 수 없습니다."));
        if (r.courseId() != null && jdbc.queryForObject(
            "select count(*) from courses where id=? and mountain_id=?", Long.class,
            r.courseId(), r.mountainId()) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "산과 코스가 일치하지 않습니다.");
        if (activities.existsByMemberIdAndMountainIdAndStartedAtAndEndedAt(member.getId(), r.mountainId(), r.startedAt(), r.endedAt()))
            throw HikingActivityConflict.duplicate();
        // Submitted GPS evidence is checked; this does not attest a device's physical location.
        return response(activities.saveAndFlush(new HikingActivity(member, mountain.name(), r,
            GpsEvidence.reachedSummit(r, mountain.lat(), mountain.lng()), HikingRequestFingerprint.of(r))));
    }
    private record Summit(String name, double lat, double lng) {}
}
