package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.ActivityScoringResponse;
import com.ggmount.hiking.dto.ScoreSummaryResponse;
import com.ggmount.hiking.repository.HikingActivityRepository;
import com.ggmount.member.domain.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Date;
import java.util.*;

/**
 * 방문·정상 점수 계산은 정책 클래스에 위임하고, 활동별 결과·공개 일지 보너스·회원 총점과 변경 이력을 저장한다.
 * 호출자의 기존 트랜잭션에 참여해야 하며, 활동·일지 변경 전 회원 잠금을 얻는 순서로 호출한다.
 * 산행 신규 저장·동일 재전송, 일지 생성·수정·삭제 및 명시적 재산정에서 현재 자료로 점수를 다시 계산한다.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class HikingScoreService {
    private final JdbcTemplate jdbc;
    private final HikingActivityRepository activities;
    private final jakarta.persistence.EntityManager entityManager;
    public HikingScoreService(JdbcTemplate jdbc, HikingActivityRepository activities, jakarta.persistence.EntityManager entityManager) {
        this.jdbc = jdbc; this.activities = activities; this.entityManager = entityManager;
    }
    // 현재 트랜잭션에서 해당 회원 행을 잠가 같은 회원의 점수 변경을 순차 처리한다.
    public void lock(Member member) {
        jdbc.queryForObject("select id from members where id=? for update", Long.class, member.getId());
        // 잠금 대기 전에 읽은 회원 상태를 갱신해 다른 트랜잭션의 점수 변경을 최신 값으로 반영한다.
        // 오래된 값과의 비교 때문에 점수 회수가 변경 없음으로 오인되지 않도록 한다.
        entityManager.refresh(member);
    }
    private record Previous(String status, int visit, int summit, String version, java.time.LocalDate date) {
        long points() { return visit + summit; }
    }
    private record PreviousBonus(int points, String version) {}
    // 호출자가 회원 잠금을 획득한 상태에서 실행하며, 이 메서드 자체에서 잠금을 다시 얻지는 않는다.
    public ScoreSummaryResponse rebuildLocked(Member member) {
        return rebuildLocked(member, null);
    }
    public ScoreSummaryResponse rebuildLocked(Member member, Long newActivityId) {
        long memberId = member.getId();
        // 공개 상태 조회 전에 영속성 컨텍스트에 남은 일지 수정·삭제를 DB에 반영한다.
        activities.flush();
        Map<Long, Previous> previous = new HashMap<>();
        jdbc.query("select activity_id,status,visit_points,summit_points,policy_version,certification_date from hiking_activity_scores where member_id=?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> previous.put(rs.getLong(1),
                new Previous(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getDate(6).toLocalDate())), memberId);
        var input = new ArrayList<HikingScorePolicy.Activity>();
        var awards = new ArrayList<HikingScorePolicy.Award>();
        var historicalVisited = new HashSet<Long>();
        for (var a : activities.findByMemberIdOrderByStartedAtDescIdDesc(memberId)) {
            var old = previous.get(a.getId());
            if (old != null && !HikingScorePolicy.VERSION.equals(old.version())) {
                // 구정책 결과를 소급 변경하지 않는다. 승인 없는 과거 재인증·일일 원장 삭제도 하지 않는다.
                awards.add(new HikingScorePolicy.Award(a.getId(), a.getMountainId(), old.date(), old.status(), old.visit(), old.summit()));
                if ("CERTIFIED".equals(old.status())) historicalVisited.add(a.getMountainId());
            } else if (old != null || Objects.equals(a.getId(), newActivityId)) {
                boolean verifiedSummit = a.isCompleted() && Objects.equals(a.getGpsValidationVersion(), GpsEvidence.VALIDATION_VERSION);
                input.add(new HikingScorePolicy.Activity(a.getId(), a.getMountainId(), a.getStartedAt(),
                    a.getDistanceMeters(), a.getElapsedMs(), a.isCompleted(), a.isGpsRecorded(), verifiedSummit));
            }
        }
        awards.addAll(HikingScorePolicy.calculate(input, historicalVisited));
        long visitTotal = 0, summitTotal = 0;
        for (var award : awards) {
            Previous old = previous.get(award.activityId());
            if (old != null && !HikingScorePolicy.VERSION.equals(old.version())) {
                visitTotal = Math.addExact(visitTotal, old.visit());
                summitTotal = Math.addExact(summitTotal, old.summit());
                continue;
            }
            if (old == null) {
                jdbc.update("insert into hiking_activity_scores(activity_id,member_id,mountain_id,certification_date,status,visit_points,summit_points,policy_version) values(?,?,?,?,?,?,?,?)",
                    award.activityId(), memberId, award.mountainId(), Date.valueOf(award.certificationDate()),
                    award.status(), award.visitPoints(), award.summitPoints(), HikingScorePolicy.VERSION);
            } else {
                jdbc.update("update hiking_activity_scores set certification_date=?,status=?,visit_points=?,summit_points=?,policy_version=? where activity_id=? and member_id=?",
                    Date.valueOf(award.certificationDate()), award.status(), award.visitPoints(), award.summitPoints(),
                    HikingScorePolicy.VERSION, award.activityId(), memberId);
            }
            // 활동별 결과는 삽입·갱신하고, 신규 결과 또는 상태·점수 변경이 있을 때만 활동 변경 이력을 기록한다.
            if (old == null || !old.status().equals(award.status()) || old.visit() != award.visitPoints() || old.summit() != award.summitPoints()) {
                event(memberId, award.activityId(), award.mountainId(), "ACTIVITY", old == null ? 0L : old.points(),
                    award.points(), (old == null ? "NEW" : old.status()) + "->" + award.status()
                        + ";visit=" + (old == null ? 0 : old.visit()) + "->" + award.visitPoints()
                        + ";summit=" + (old == null ? 0 : old.summit()) + "->" + award.summitPoints());
            }
            visitTotal = Math.addExact(visitTotal, award.visitPoints());
            summitTotal = Math.addExact(summitTotal, award.summitPoints());
        }
        // 방문 인증과 별도로 본인 활동에 연결된 공개 일지가 있는 산마다 50점을 부여한다. 같은 산의 여러 일지는 중복 가산하지 않는다.
        // 활동 인증 상태·GPS 검증 여부는 이 쿼리에서 검사하지 않으며, 활동 연결이 없는 기존 일지는 제외한다.
        Set<Long> publicMountains = new HashSet<>(jdbc.queryForList(
            "select distinct a.mountain_id from hiking_records j join hiking_activities a on a.id=j.hiking_activity_id where j.member_id=? and a.member_id=j.member_id and j.is_public=true",
            Long.class, memberId));
        Map<Long, PreviousBonus> previousBonuses = new HashMap<>();
        jdbc.query("select mountain_id,points,policy_version from hiking_public_bonuses where member_id=?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> previousBonuses.put(rs.getLong(1),
                new PreviousBonus(rs.getInt(2), rs.getString(3))), memberId);
        // 구정책 보너스는 금액·버전을 보존하고, 현재 정책 보너스만 공개 상태에 따라 지급·회수한다.
        Set<Long> allMountains = new HashSet<>(previousBonuses.keySet()); allMountains.addAll(publicMountains);
        long bonusTotal = 0;
        for (long mountainId : allMountains) {
            PreviousBonus previousBonus = previousBonuses.get(mountainId);
            if (previousBonus != null && !HikingScorePolicy.VERSION.equals(previousBonus.version())) {
                bonusTotal = Math.addExact(bonusTotal, previousBonus.points());
                continue;
            }
            int points = publicMountains.contains(mountainId) ? 50 : 0;
            Integer old = previousBonus == null ? null : previousBonus.points();
            if (old == null) jdbc.update("insert into hiking_public_bonuses(member_id,mountain_id,points,policy_version) values(?,?,?,?)",
                memberId, mountainId, points, HikingScorePolicy.VERSION);
            else jdbc.update("update hiking_public_bonuses set points=?,policy_version=? where member_id=? and mountain_id=?",
                points, HikingScorePolicy.VERSION, memberId, mountainId);
            if (old == null || old != points) event(memberId, null, mountainId, "PUBLIC_BONUS", old == null ? 0L : old.longValue(), points,
                points == 50 ? "PUBLIC_JOURNAL_PRESENT" : "NO_PUBLIC_JOURNAL");
            bonusTotal = Math.addExact(bonusTotal, points);
        }
        // 정수의 정확한 합을 사용하며 범위를 넘으면 예외를 전파한다. 총점은 방문·정상·공개 일지 보너스의 합이다.
        long total = Math.addExact(Math.addExact(visitTotal, summitTotal), bonusTotal);
        Long oldTotal = jdbc.queryForObject("select score from members where id=?", Long.class, memberId);
        if (oldTotal == null || oldTotal != total) event(memberId, null, null, "MEMBER_TOTAL", oldTotal, total, "REBUILD_FROM_CURRENT_SOURCES");
        // 총점 변경 이력은 값이 달라질 때 기록하고, 회원 점수는 현재 총점으로 교체한다. 랭킹 저장소는 이 점수로 정렬한다.
        member.replaceHikingScore(total);
        // 같은 트랜잭션에서 DB에 반영해 실패 시 활동·일지·점수 변경이 함께 롤백되도록 한다.
        activities.flush();
        return new ScoreSummaryResponse(total, visitTotal, summitTotal, bonusTotal, HikingScorePolicy.VERSION);
    }
    // 이전 값이 없으면 증감 계산에서 0으로 취급하고 정책 버전과 변경 사유를 같은 트랜잭션에 기록한다.
    private void event(long memberId, Long activityId, Long mountainId, String kind, Long oldPoints, long newPoints, String detail) {
        jdbc.update("insert into hiking_score_events(member_id,activity_id,mountain_id,kind,old_points,new_points,points_delta,detail,policy_version) values(?,?,?,?,?,?,?,?,?)",
            memberId, activityId, mountainId, kind, oldPoints, newPoints,
            Math.subtractExact(newPoints, oldPoints == null ? 0 : oldPoints), detail, HikingScorePolicy.VERSION);
    }
    // 산행 응답에 사용할 저장된 활동 점수만 조회하며, 결과가 없으면 계산을 새로 하지 않고 null을 반환한다.
    public ActivityScoringResponse activityScore(long activityId) {
        return jdbc.query("select status,certification_date,visit_points,summit_points,policy_version from hiking_activity_scores where activity_id=?",
            (rs, i) -> new ActivityScoringResponse(rs.getString(1), rs.getDate(2).toLocalDate(),
                rs.getInt(3), rs.getInt(4), rs.getString(5)), activityId).stream().findFirst().orElse(null);
    }
}
