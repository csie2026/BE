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

/** All writers acquire the same member lock BEFORE activity/journal locks and modifications.
 * Rebuild replaces projections, never increments balances blindly. Changes are audited in the
 * same transaction; late activity upload reorders first visits/daily eligibility deterministically.
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
    public void lock(Member member) {
        jdbc.queryForObject("select id from members where id=? for update", Long.class, member.getId());
        // A waiting transaction may have loaded Member before another writer committed. Refresh
        // so Hibernate dirty checking cannot mistake a score revocation for an unchanged value.
        entityManager.refresh(member);
    }
    private record Previous(String status, int visit, int summit) {
        long points() { return visit + summit; }
    }
    public ScoreSummaryResponse rebuildLocked(Member member) {
        long memberId = member.getId();
        // Materialize pending journal updates/deletes before querying its publication state.
        activities.flush();
        List<HikingScorePolicy.Activity> input = activities.findByMemberIdOrderByStartedAtDescIdDesc(memberId)
            .stream().map(a -> new HikingScorePolicy.Activity(a.getId(), a.getMountainId(), a.getStartedAt(),
                a.getDistanceMeters(), a.getElapsedMs(), a.isCompleted(), a.isGpsRecorded())).toList();
        List<HikingScorePolicy.Award> awards = HikingScorePolicy.calculate(input);
        Map<Long, Previous> previous = new HashMap<>();
        jdbc.query("select activity_id,status,visit_points,summit_points from hiking_activity_scores where member_id=?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> previous.put(rs.getLong(1),
                new Previous(rs.getString(2), rs.getInt(3), rs.getInt(4))), memberId);
        jdbc.update("delete from hiking_daily_certifications where member_id=?", memberId);
        long visitTotal = 0, summitTotal = 0;
        for (var award : awards) {
            Previous old = previous.get(award.activityId());
            if (old == null) {
                jdbc.update("insert into hiking_activity_scores(activity_id,member_id,mountain_id,certification_date,status,visit_points,summit_points,policy_version) values(?,?,?,?,?,?,?,?)",
                    award.activityId(), memberId, award.mountainId(), Date.valueOf(award.certificationDate()),
                    award.status(), award.visitPoints(), award.summitPoints(), HikingScorePolicy.VERSION);
            } else {
                jdbc.update("update hiking_activity_scores set certification_date=?,status=?,visit_points=?,summit_points=?,policy_version=? where activity_id=? and member_id=?",
                    Date.valueOf(award.certificationDate()), award.status(), award.visitPoints(), award.summitPoints(),
                    HikingScorePolicy.VERSION, award.activityId(), memberId);
            }
            if (old == null || !old.status().equals(award.status()) || old.visit() != award.visitPoints() || old.summit() != award.summitPoints()) {
                event(memberId, award.activityId(), award.mountainId(), "ACTIVITY", old == null ? 0L : old.points(),
                    award.points(), (old == null ? "NEW" : old.status()) + "->" + award.status()
                        + ";visit=" + (old == null ? 0 : old.visit()) + "->" + award.visitPoints()
                        + ";summit=" + (old == null ? 0 : old.summit()) + "->" + award.summitPoints());
            }
            if (award.certified()) jdbc.update("insert into hiking_daily_certifications(member_id,certification_date,activity_id) values(?,?,?)",
                memberId, Date.valueOf(award.certificationDate()), award.activityId());
            visitTotal = Math.addExact(visitTotal, award.visitPoints());
            summitTotal = Math.addExact(summitTotal, award.summitPoints());
        }
        // Bonus policy is separate from visit certification: any owned GPS-activity-linked public
        // journal qualifies, even a daily-limit activity. Legacy journals without activity do not.
        Set<Long> publicMountains = new HashSet<>(jdbc.queryForList(
            "select distinct a.mountain_id from hiking_records j join hiking_activities a on a.id=j.hiking_activity_id where j.member_id=? and a.member_id=j.member_id and j.is_public=true",
            Long.class, memberId));
        Map<Long, Integer> previousBonuses = new HashMap<>();
        jdbc.query("select mountain_id,points from hiking_public_bonuses where member_id=?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> previousBonuses.put(rs.getLong(1), rs.getInt(2)), memberId);
        Set<Long> allMountains = new HashSet<>(previousBonuses.keySet()); allMountains.addAll(publicMountains);
        for (long mountainId : allMountains) {
            int points = publicMountains.contains(mountainId) ? 50 : 0;
            Integer old = previousBonuses.get(mountainId);
            if (old == null) jdbc.update("insert into hiking_public_bonuses(member_id,mountain_id,points,policy_version) values(?,?,?,?)",
                memberId, mountainId, points, HikingScorePolicy.VERSION);
            else jdbc.update("update hiking_public_bonuses set points=?,policy_version=? where member_id=? and mountain_id=?",
                points, HikingScorePolicy.VERSION, memberId, mountainId);
            if (old == null || old != points) event(memberId, null, mountainId, "PUBLIC_BONUS", old == null ? 0L : old.longValue(), points,
                points == 50 ? "PUBLIC_JOURNAL_PRESENT" : "NO_PUBLIC_JOURNAL");
        }
        long bonusTotal = Math.multiplyExact((long) publicMountains.size(), 50L);
        long total = Math.addExact(Math.addExact(visitTotal, summitTotal), bonusTotal);
        Long oldTotal = jdbc.queryForObject("select score from members where id=?", Long.class, memberId);
        if (oldTotal == null || oldTotal != total) event(memberId, null, null, "MEMBER_TOTAL", oldTotal, total, "REBUILD_FROM_CURRENT_SOURCES");
        member.replaceHikingScore(total);
        // Flush within this transaction, so any DB failure rolls back activity, journal and score.
        activities.flush();
        return new ScoreSummaryResponse(total, visitTotal, summitTotal, bonusTotal, HikingScorePolicy.VERSION);
    }
    private void event(long memberId, Long activityId, Long mountainId, String kind, Long oldPoints, long newPoints, String detail) {
        jdbc.update("insert into hiking_score_events(member_id,activity_id,mountain_id,kind,old_points,new_points,points_delta,detail,policy_version) values(?,?,?,?,?,?,?,?,?)",
            memberId, activityId, mountainId, kind, oldPoints, newPoints,
            Math.subtractExact(newPoints, oldPoints == null ? 0 : oldPoints), detail, HikingScorePolicy.VERSION);
    }
    public ActivityScoringResponse activityScore(long activityId) {
        return jdbc.query("select status,certification_date,visit_points,summit_points,policy_version from hiking_activity_scores where activity_id=?",
            (rs, i) -> new ActivityScoringResponse(rs.getString(1), rs.getDate(2).toLocalDate(),
                rs.getInt(3), rs.getInt(4), rs.getString(5)), activityId).stream().findFirst().orElse(null);
    }
}
