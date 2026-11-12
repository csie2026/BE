package com.ggmount;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import com.ggmount.member.domain.Member;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.OAuthMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:public_profile_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:public_profile_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password=",
    "app.scoring.rebuild-on-start=false"
})
@Transactional
class PublicProfileStatisticsIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired OAuthMemberService oauth;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    Account owner;
    Account viewer;
    int activitySequence;
    record Account(Member member, MockHttpSession session) {}

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
        owner = account("public-stats-owner", true);
        viewer = account("public-stats-viewer", true);
    }

    Account account(String providerId, boolean complete) {
        var info = new OAuthUserInfo("google", providerId, "private@example.invalid", "Writer", null);
        var member = oauth.processLogin(info);
        if (complete) member.completeProfile("Writer", 2003);
        members.flush();
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", providerId), "sub", info, member.getId());
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return new Account(member, session);
    }

    // 집계용 기존 활동을 준비하며, GPS 저장 판정은 기존 통합 테스트에서 검증한다.
    long activity(Account account, boolean completed) {
        var requestId = UUID.randomUUID().toString();
        var startedAt = java.time.OffsetDateTime.parse("2020-01-01T01:00:00Z").plusHours(activitySequence++);
        jdbc.update("""
            INSERT INTO hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,
                distance_meters,elapsed_ms,completed,client_request_id,gps_recorded,
                gps_validation_version,request_fingerprint_version,request_fingerprint)
            VALUES(?,1,'Test mountain',?,?,500,1200000,?,?,FALSE,1,1,?)
            """, account.member().getId(), startedAt, startedAt.plusMinutes(20), completed, requestId, "a".repeat(64));
        return jdbc.queryForObject("select id from hiking_activities where client_request_id=?", Long.class, requestId);
    }

    long activity(double meters, long elapsed, boolean completed, Integer version) {
        long id = activity(owner, completed);
        jdbc.update("""
            update hiking_activities set distance_meters=?,elapsed_ms=?,gps_validation_version=?,
                request_fingerprint_version=?,request_fingerprint=? where id=?
            """, meters, elapsed, version, version == null ? null : 1, version == null ? null : "a".repeat(64), id);
        return id;
    }

    void score(long activityId, String status) {
        jdbc.update("""
            INSERT INTO hiking_activity_scores(activity_id,member_id,mountain_id,certification_date,
                status,visit_points,summit_points,policy_version)
            VALUES(?,?,1,'2020-01-01',?,?,0,'legacy')
            """, activityId, owner.member().getId(), status, status.equals("CERTIFIED") ? 200 : 0);
    }

    long journal(Account account, long activityId, boolean visible) {
        jdbc.update("""
            INSERT INTO hiking_records(member_id,mountain_name,title,content,hiking_date,is_public,hiking_activity_id)
            VALUES(?,'Test mountain','Public trip','Full text','2020-01-01',?,?)
            """, account.member().getId(), visible, activityId);
        return jdbc.queryForObject("select id from hiking_records where hiking_activity_id=?", Long.class, activityId);
    }

    String profile() { return "/api/users/" + owner.member().getId() + "/profile"; }
    void counts(int total, int completed) throws Exception {
        mvc.perform(get(profile()).session(viewer.session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalHikeCount").value(total))
            .andExpect(jsonPath("$.completedHikeCount").value(completed));
    }

    @Test void countsEligibleSavedActivitiesWithoutRequiringJournalsAndExcludesOtherMembers() throws Exception {
        activity(owner, false);
        activity(owner, true);
        activity(owner, true);
        activity(viewer, true);
        counts(3, 2);
    }

    @Test void thresholdsAndVerifiedSummitsCountIndependentlyWithInclusiveBoundaries() throws Exception {
        activity(500, 1200000, false, null); // 경계값 일반 산행, GPS 검증·점수 원장 없음
        counts(1, 0);
        activity(499.999, 1200000, false, 1);
        activity(500, 1199999, false, 1);
        activity(100, 1000, false, 1);
        counts(1, 0);
        activity(100, 1000, true, 1); // 짧은 검증 완등
        counts(2, 1);
        activity(500, 1200000, true, 1); // 두 조건을 만족해도 활동 한 번만 인정
        counts(3, 2);
        activity(100, 1000, true, null); // 검증 근거 없는 구버전 완료
        counts(3, 2);
    }

    @Test void legacyDailyLimitAndMissingLedgersCannotSuppressRepeatedSameDayActivities() throws Exception {
        long first = activity(500, 1200000, false, null);
        score(first, "DAILY_LIMIT");
        long second = activity(100, 1000, true, 1);
        score(second, "DAILY_LIMIT");
        long third = activity(100, 1000, true, 1); // 같은 산·같은 날 반복 완등, 원장 없음
        long fourth = activity(500, 1200000, false, null);
        jdbc.update("update hiking_activities set mountain_id=2 where id=?", fourth);
        score(fourth, "GPS_REQUIRED");
        long failed = activity(100, 1000, true, null);
        score(failed, "CERTIFIED"); // 점수 인증·구버전 완료만으로 횟수를 만들지 않는다.
        counts(4, 2);
        assertNotEquals(second, third);
    }

    @Test void ownActivityResponseAndPublicCountsUseSameEvidenceAndNeverRewriteScoreLedgers() throws Exception {
        activity(500, 1200000, false, null);
        long summit = activity(100, 1000, true, 1);
        score(summit, "DAILY_LIMIT");
        activity(100, 1000, true, null);
        owner.member().replaceHikingScore(987);
        members.flush();
        var scores = jdbc.queryForList("select * from hiking_activity_scores where member_id=?", owner.member().getId());
        var daily = jdbc.queryForList("select * from hiking_daily_certifications where member_id=?", owner.member().getId());
        var events = jdbc.queryForList("select * from hiking_score_events where member_id=?", owner.member().getId());
        counts(2, 1);
        mvc.perform(get(profile()).session(owner.session()))
            .andExpect(jsonPath("$.totalHikeCount").value(2)).andExpect(jsonPath("$.completedHikeCount").value(1))
            .andExpect(jsonPath("$.score").value(987));
        mvc.perform(get("/api/users/me").session(owner.session())).andExpect(jsonPath("$.score").value(987));
        mvc.perform(get(profile()).session(viewer.session())).andExpect(jsonPath("$.score").value(987));

        // FE가 받는 본인 활동 응답으로 동일 판정을 수행해 공개 집계와 대조한다.
        String json = mvc.perform(get("/api/users/me/hiking-records").session(owner.session()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var records = new tools.jackson.databind.ObjectMapper().readTree(json);
        int hikes = 0, summits = 0;
        for (var record : records) {
            boolean verified = record.path("completed").asBoolean() && record.path("gpsValidationVersion").asInt() == 1;
            if (verified) summits++;
            if (verified || (record.path("distanceMeters").asDouble() >= 500 && record.path("elapsedMs").asLong() >= 1200000)) hikes++;
        }
        assertEquals(2, hikes);
        assertEquals(1, summits);
        assertEquals(scores, jdbc.queryForList("select * from hiking_activity_scores where member_id=?", owner.member().getId()));
        assertEquals(daily, jdbc.queryForList("select * from hiking_daily_certifications where member_id=?", owner.member().getId()));
        assertEquals(events, jdbc.queryForList("select * from hiking_score_events where member_id=?", owner.member().getId()));
    }

    @Test void publicJournalListHidesPrivateRecordsWhileCountsIncludeBothActivities() throws Exception {
        long publicId = journal(owner, activity(owner, true), true);
        long privateId = journal(owner, activity(owner, false), false);
        journal(viewer, activity(viewer, true), true);
        counts(2, 1);
        mvc.perform(get("/api/users/" + owner.member().getId() + "/journals").session(viewer.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(publicId)).andExpect(jsonPath("$[0].isPublic").value(true));
        mvc.perform(get("/api/journals/" + publicId).session(viewer.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Full text"));
        mvc.perform(get("/api/journals/" + privateId).session(viewer.session()))
            .andExpect(status().isNotFound());
    }

    @Test void changingOrDeletingAJournalDoesNotChangeGpsCountsAndRepeatSummitsStaySeparate() throws Exception {
        long first = journal(owner, activity(owner, true), true);
        long second = journal(owner, activity(owner, true), false);
        counts(2, 2);
        jdbc.update("update hiking_records set is_public=false where id=?", first);
        counts(2, 2);
        jdbc.update("delete from hiking_records where id in (?,?)", first, second);
        counts(2, 2);
    }

    @Test void unlinkedLegacyPublicJournalCannotInventGpsHikes() throws Exception {
        jdbc.update("""
            INSERT INTO hiking_records(member_id,mountain_name,title,content,hiking_date,is_public)
            VALUES(?,'Legacy mountain','Legacy trip','Old text','2019-01-01',TRUE)
            """, owner.member().getId());
        counts(0, 0);
        mvc.perform(get("/api/users/" + owner.member().getId() + "/journals").session(viewer.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].hikingRecordId").isEmpty());
    }

    @Test void emptyHistoryReturnsZeroCountsWithoutInventingAnUncalculatedScore() throws Exception {
        counts(0, 0);
        mvc.perform(get(profile()).session(viewer.session()))
            .andExpect(jsonPath("$.score").isEmpty());
        owner.member().replaceHikingScore(0);
        members.flush();
        mvc.perform(get(profile()).session(viewer.session()))
            .andExpect(jsonPath("$.score").value(0));
    }

    @Test void freshReadsReflectNewActivitiesAndNeverExposePrivateAccountOrGpsFields() throws Exception {
        counts(0, 0);
        activity(owner, true);
        mvc.perform(get(profile()).session(viewer.session()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.totalHikeCount").value(1)).andExpect(jsonPath("$.completedHikeCount").value(1))
            .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.birthYear").doesNotExist())
            .andExpect(jsonPath("$.providerId").doesNotExist()).andExpect(jsonPath("$.provider").doesNotExist())
            .andExpect(jsonPath("$.joinedAt").doesNotExist()).andExpect(jsonPath("$.backgroundImageUrl").doesNotExist())
            .andExpect(jsonPath("$.activities").doesNotExist()).andExpect(jsonPath("$.gpsEvidence").doesNotExist());
    }

    @Test void authenticationMissingMembersAndIncompleteProfilesKeepExistingRestrictions() throws Exception {
        mvc.perform(get(profile())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/9223372036854775807/profile").session(viewer.session()))
            .andExpect(status().isNotFound());
        var incomplete = account("public-stats-incomplete", false);
        mvc.perform(get("/api/users/" + incomplete.member().getId() + "/profile").session(viewer.session()))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/" + incomplete.member().getId() + "/journals").session(viewer.session()))
            .andExpect(status().isForbidden());
    }
}
