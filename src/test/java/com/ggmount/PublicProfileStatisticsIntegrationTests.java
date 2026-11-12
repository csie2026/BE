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
            VALUES(?,1,'Test mountain',?,?,0,1200000,?,?,FALSE,NULL,NULL,NULL)
            """, account.member().getId(), startedAt, startedAt.plusMinutes(20), completed, requestId);
        return jdbc.queryForObject("select id from hiking_activities where client_request_id=?", Long.class, requestId);
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

    @Test void countsAllSavedActivitiesWithoutRequiringJournalsAndExcludesOtherMembers() throws Exception {
        activity(owner, false);
        activity(owner, true);
        activity(owner, true);
        activity(viewer, true);
        counts(3, 2);
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
