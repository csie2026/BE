package com.ggmount;

import com.ggmount.global.auth.oauth.*;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.OAuthMemberService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:journal_eligibility;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:journal_eligibility;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password="
})
@Transactional
class JournalEligibilityIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired OAuthMemberService oauth;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired DataSource dataSource;
    MockMvc mvc;
    MockHttpSession owner;
    long memberId;
    int sequence;

    OAuthUserInfo info(String identity) {
        return new OAuthUserInfo("google", identity, "eligibility@example.invalid", "Journal", null);
    }
    MockHttpSession login(String identity, boolean complete) {
        var member = oauth.processLogin(info(identity));
        if (complete) member.completeProfile("Journal", 2000);
        members.flush();
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", identity), "sub", info(identity), member.getId());
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }
    @BeforeEach void setup() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:journal_eligibility"));
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
        String identity = "journal-eligibility-" + UUID.randomUUID();
        owner = login(identity, true);
        memberId = members.findByProviderAndProviderId("google", identity).orElseThrow().getId();
    }
    String csrf(MockHttpSession session) throws Exception {
        String json = mvc.perform(get("/api/csrf").session(session)).andReturn().getResponse().getContentAsString();
        return json.split("\"token\":\"")[1].split("\"")[0];
    }
    // 저장된 활동 값의 경계 테스트이며 운영 DB나 실제 GPS에는 연결하지 않는다.
    long activity(double distance, long elapsed, boolean completed, Short version, long mountain) {
        var started = Instant.parse("2020-01-01T01:00:00Z").plusSeconds(sequence++);
        jdbc.update("""
            insert into hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,
                distance_meters,elapsed_ms,completed,client_request_id,gps_validation_version,
                request_fingerprint_version,request_fingerprint)
            values(?,?,?,?,?,?,?,?,?,?,?,?)
            """, memberId, mountain, "Journal mountain", started, started.plusSeconds(1800),
            distance, elapsed, completed, UUID.randomUUID().toString(), version,
            version == null ? null : 1, version == null ? null : "0".repeat(64));
        return jdbc.queryForObject("select max(id) from hiking_activities where member_id=?", Long.class, memberId);
    }
    ResultActions create(MockHttpSession session, long activity, boolean visible) throws Exception {
        return mvc.perform(post("/api/journals").session(session).header("X-CSRF-TOKEN", csrf(session))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hikingRecordId\":" + activity + ",\"title\":\"Journal\",\"content\":\"\",\"isPublic\":" + visible + "}"));
    }
    long journalId(long activity) {
        return jdbc.queryForObject("select id from hiking_records where hiking_activity_id=?", Long.class, activity);
    }
    void visibility(long id, boolean visible) throws Exception {
        mvc.perform(patch("/api/journals/" + id).session(owner).header("X-CSRF-TOKEN", csrf(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Updated\",\"content\":\"\",\"isPublic\":" + visible + "}"))
            .andExpect(status().isOk());
    }
    void deleteJournal(long id) throws Exception {
        mvc.perform(delete("/api/journals/" + id).session(owner).header("X-CSRF-TOKEN", csrf(owner)))
            .andExpect(status().isNoContent());
    }
    @Test void generalBoundariesUseSavedDistanceAndActiveTimeWithoutGpsOrScoreRequirement() throws Exception {
        create(owner, activity(500, 1200000, false, null, 1), false).andExpect(status().isOk());
        create(owner, activity(499.999, 1200000, false, (short) 1, 1), false).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("request_failed"));
        create(owner, activity(500, 1199000, false, (short) 1, 1), false).andExpect(status().isBadRequest());
        create(owner, activity(600, 1500000, false, (short) 1, 1), false).andExpect(status().isOk());
        create(owner, activity(0, 0, false, (short) 1, 1), false).andExpect(status().isBadRequest());
        assertEquals(2, jdbc.queryForObject("select count(*) from hiking_records where member_id=?", Integer.class, memberId));
    }
    @Test void shortVerifiedSummitIsEligibleButUnverifiedLegacyCompletionIsNot() throws Exception {
        create(owner, activity(200, 60000, true, (short) 1, 1), false).andExpect(status().isOk());
        create(owner, activity(200, 60000, true, null, 1), false).andExpect(status().isBadRequest());
    }
    @Test void requestCannotOverrideStoredEligibilityValues() throws Exception {
        long activity = activity(0, 0, false, (short) 1, 1);
        mvc.perform(post("/api/journals").session(owner).header("X-CSRF-TOKEN", csrf(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hikingRecordId\":" + activity + ",\"title\":\"Forged\",\"content\":\"\",\"isPublic\":true,"
                + "\"distanceMeters\":500,\"elapsedMs\":1200000,\"completed\":true,\"gpsValidationVersion\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("request_failed"));
        assertEquals(0, jdbc.queryForObject("select count(*) from hiking_records where member_id=?", Integer.class, memberId));
    }
    @Test void sameDayThreeActivitiesAndSameMountainAreNotJournalCountLimits() throws Exception {
        for (int i = 0; i < 3; i++) create(owner, activity(500, 1200000, false, (short) 1, 1), true)
            .andExpect(status().isOk());
        assertEquals(3, jdbc.queryForObject("select count(*) from hiking_records where member_id=?", Integer.class, memberId));
        assertEquals(50L, jdbc.queryForObject("select score from members where id=?", Long.class, memberId));
    }
    @Test void ownershipProfileAndDuplicateProtectionRemainInForce() throws Exception {
        long id = activity(500, 1200000, false, (short) 1, 1);
        create(login("other-" + UUID.randomUUID(), true), id, false).andExpect(status().isNotFound());
        create(login("incomplete-" + UUID.randomUUID(), false), id, false).andExpect(status().isForbidden());
        create(owner, id, false).andExpect(status().isOk());
        create(owner, id, false).andExpect(status().isConflict());
    }
    @Test void deletionAllowsRecreationForTheSameEligibleActivity() throws Exception {
        long activity = activity(500, 1200000, false, (short) 1, 1);
        create(owner, activity, true).andExpect(status().isOk());
        deleteJournal(journalId(activity));
        create(owner, activity, true).andExpect(status().isOk());
        assertEquals(50L, jdbc.queryForObject("select score from members where id=?", Long.class, memberId));
    }
    @Test void existingIneligibleJournalCanStillBeReadEditedDeletedButNotRecreated() throws Exception {
        long activity = activity(500, 1200000, false, (short) 1, 1);
        create(owner, activity, false).andExpect(status().isOk());
        long journal = journalId(activity);
        jdbc.update("update hiking_activities set distance_meters=0,elapsed_ms=0 where id=?", activity);
        entities.clear();
        mvc.perform(get("/api/journals/" + journal).session(owner)).andExpect(status().isOk());
        visibility(journal, true);
        visibility(journal, false);
        deleteJournal(journal);
        create(owner, activity, false).andExpect(status().isBadRequest());
    }
    @Test void publicBonusStillPaysPerMountainWithoutADailyLimitAndPreservesVisitLedgers() throws Exception {
        long first = activity(500, 1200000, false, (short) 1, 1);
        long second = activity(500, 1200000, false, (short) 1, 2);
        create(owner, first, true).andExpect(status().isOk());
        create(owner, second, true).andExpect(status().isOk());
        assertEquals(100L, jdbc.queryForObject("select score from members where id=?", Long.class, memberId));
        visibility(journalId(first), false);
        assertEquals(50L, jdbc.queryForObject("select score from members where id=?", Long.class, memberId));
        visibility(journalId(first), true);
        assertEquals(100L, jdbc.queryForObject("select score from members where id=?", Long.class, memberId));
        assertEquals(0, jdbc.queryForObject("select count(*) from hiking_activity_scores where member_id=?", Integer.class, memberId));
    }
}
