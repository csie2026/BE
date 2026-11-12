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
    private final HikingScoreService scores;
    public HikingActivityService(HikingActivityRepository activities, HikingRecordRepository journals,
        MemberService members, JdbcTemplate jdbc, HikingScoreService scores) {
        this.activities = activities; this.journals = journals; this.members = members; this.jdbc = jdbc; this.scores = scores;
    }
    private HikingActivityResponse response(HikingActivity a) {
        return HikingActivityResponse.from(a, journals.findByActivityId(a.getId())
            .map(com.ggmount.hiking.domain.HikingRecord::getId).orElse(null), scores.activityScore(a.getId()));
    }
    // 세션의 내부 회원 ID와 공급자 정보를 대조한 회원의 산행만 시작 시각·ID 내림차순으로 조회한다.
    public List<HikingActivityResponse> mine(OAuthPrincipal p) {
        return activities.findByMemberIdOrderByStartedAtDescIdDesc(members.current(p).getId())
            .stream().map(this::response).toList();
    }
    // 산행 ID와 본인 회원 ID를 함께 조회해 소유권을 확인하며, 없거나 다른 회원의 산행이면 404다.
    public HikingActivityResponse detail(OAuthPrincipal p, Long id) {
        return response(activities.findByIdAndMemberId(id, members.current(p).getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
    @Transactional
    // 프로필 완료 회원의 행을 잠근 뒤 점수를 재산정하며, 조회 API에는 이 프로필 완료 검사를 적용하지 않는다.
    public ScoreSummaryResponse recalculate(OAuthPrincipal p) {
        var member = members.current(p);
        members.requireComplete(member);
        scores.lock(member);
        return scores.rebuildLocked(member);
    }
    @Transactional
    // 회원 잠금 아래 재전송 판별·산행 저장·점수 재산정을 수행하고, 런타임 예외가 발생하면 함께 롤백한다.
    public HikingActivityResponse create(OAuthPrincipal p, HikingActivityRequest r) {
        var member = members.current(p);
        members.requireComplete(member);
        // GPS는 시각 반올림 전 원본으로 검증하고, 저장·지문 비교에는 정규화한 요청을 사용한다.
        var raw = r;
        r = r.normalizedForStorage();
        // 요청 ID 조회 전에 회원 행을 잠가 같은 계정의 동시 재전송과 점수 변경을 순차 처리한다.
        scores.lock(member);
        var existing = activities.findAllByMemberIdAndClientRequestIdIgnoreCase(member.getId(), r.clientRequestId());
        // 같은 회원·요청 ID를 대소문자 구분 없이 조회하며, 여러 행이면 임의 선택·병합 없이 요청 충돌로 거부한다.
        if (existing.size() > 1)
            throw HikingActivityConflict.request();
        if (!existing.isEmpty()) {
            var a = existing.getFirst();
            // GPS 검증 버전이 있는 기존 기록은 경로 검증 후 지문 버전·지문·GPS 증거 해시가 모두 일치해야 한다.
            // 경로 누락이나 대조 불일치는 409이며, 경로 형식 검증 실패는 GPS 검증기의 400으로 전파한다.
            if (a.getGpsValidationVersion() != null) {
                if (raw.gpsSegments() == null) throw HikingActivityConflict.request();
                GpsEvidence.validate(raw);
                if (!Objects.equals(a.getRequestFingerprintVersion(), HikingRequestFingerprint.VERSION)
                    || !Objects.equals(a.getRequestFingerprint(), HikingRequestFingerprint.of(r)))
                    throw HikingActivityConflict.request();
                if (!Objects.equals(a.getGpsEvidenceHash(), HikingActivity.evidenceHash(r)))
                    throw HikingActivityConflict.request();
                // 동일 재전송은 새 산행을 저장하지 않고 회원 점수를 재산정한 뒤 기존 산행 응답을 반환한다.
                scores.rebuildLocked(member);
                return response(a);
            }
            // GPS 검증 버전이 없는 기존 기록은 산·코스·시각·거리·경과 시간·제출 도달 여부를 직접 비교한다.
            // 기존 GPS 증거 해시가 있을 때만 추가 대조하며, 일치한 재전송은 새 산행 없이 점수 재산정 후 반환한다.
            if (!a.getMountainId().equals(r.mountainId()) || !Objects.equals(a.getCourseId(), r.courseId())
                || !a.getStartedAt().equals(r.startedAt()) || !a.getEndedAt().equals(r.endedAt())
                || Double.compare(a.getDistanceMeters(), r.distanceMeters()) != 0
                || a.getElapsedMs() != r.elapsedMs() || a.isCompleted() != r.completed())
                throw HikingActivityConflict.request();
            if (a.getGpsEvidenceHash() != null && !Objects.equals(a.getGpsEvidenceHash(), HikingActivity.evidenceHash(r)))
                throw HikingActivityConflict.request();
            scores.rebuildLocked(member);
            return response(a);
        }
        // 같은 요청 ID의 기록이 없을 때 신규 저장으로 진행한다. 경로 검증과 조건부 거리 대조 실패는 400이다.
        GpsEvidence.validate(raw);
        GpsEvidence.validateDistance(raw);
        // 산이 없으면 404, 선택한 코스가 해당 산에 속하지 않으면 400으로 거부한다.
        var mountain = jdbc.query("select name,lat,lng from mountains where id=?",
            (rs, i) -> new Summit(rs.getString(1), rs.getDouble(2), rs.getDouble(3)), r.mountainId()).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "산을 찾을 수 없습니다."));
        if (r.courseId() != null && jdbc.queryForObject(
            "select count(*) from courses where id=? and mountain_id=?", Long.class,
            r.courseId(), r.mountainId()) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "산과 코스가 일치하지 않습니다.");
        // 요청 ID가 달라도 같은 회원·산·시작·종료 시각의 산행이 있으면 중복 산행 충돌로 거부한다.
        if (activities.existsByMemberIdAndMountainIdAndStartedAtAndEndedAt(member.getId(), r.mountainId(), r.startedAt(), r.endedAt()))
            throw HikingActivityConflict.duplicate();
        // 제출 경로로 정상 도달 여부와 지문을 저장하고 점수를 재산정한다. GPS 자료가 실제 이동을 증명하지는 않는다.
        var activity = activities.saveAndFlush(new HikingActivity(member, mountain.name(), r,
            GpsEvidence.reachedSummit(r, mountain.lat(), mountain.lng()), HikingRequestFingerprint.of(r)));
        scores.rebuildLocked(member);
        return response(activity);
    }
    private record Summit(String name, double lat, double lng) {}
}
