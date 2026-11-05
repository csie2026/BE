package com.ggmount;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.OAuthMemberService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ggmount.hiking.service.HikingActivityService;
import com.ggmount.hiking.dto.HikingActivityRequest;
import com.ggmount.hiking.dto.GpsEvidence;
import java.time.Instant;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring/validation/service/database integration, including transaction rollback and
 * concurrent writers. Runs only against an explicitly isolated H2 DB. Does not prove physical GPS
 * presence or PostgreSQL production concurrency. Full suite requires the project's Java21 setup.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:scoring_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:scoring_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password=",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jackson.deserialization.accept-float-as-int=false"
})
@Transactional
class HikingScoringIntegrationTests {
    static final String API = "/api/users/me/hiking-records";
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired OAuthMemberService oauth;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikingActivityService activityService;
    MockMvc mvc;
    MockHttpSession owner;
    String identity;
    long ownerId;
    long mountainId;
    long courseId;

    @BeforeEach
    void setup() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:scoring_review"),
                "This review must never use an external database");
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
        courseId = jdbc.queryForObject("select min(id) from courses", Long.class);
        mountainId = jdbc.queryForObject("select mountain_id from courses where id=?", Long.class, courseId);
        identity = "scoring-" + UUID.randomUUID();
        ownerId = member(identity);
        owner = login(identity);
    }
    OAuthUserInfo info(String id) {
        return new OAuthUserInfo("google", id, "integrity@example.invalid", "Integrity", null);
    }
    long member(String id) {
        var member = oauth.processLogin(info(id));
        member.completeProfile("Review " + id.substring(0, Math.min(id.length(), 8)), 2003);
        members.flush();
        return member.getId();
    }
    MockHttpSession login(String id) {
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", id), "sub", info(id));
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }
    String body(String key) {
        return """
            {"mountainId":%d,"courseId":%d,"startedAt":"2020-01-01T01:00:00Z",
             "endedAt":"2020-01-01T03:00:00Z","distanceMeters":1234.5,
             "elapsedMs":2700000,"completed":true,"clientRequestId":"%s",
             "gpsEvidence":{"latitude":37.65,"longitude":126.97,"accuracyMeters":50,"recordedAt":"2020-01-01T01:01:00Z"}}
            """.formatted(mountainId, courseId, key);
    }
    ResultActions save(MockHttpSession session, String body) throws Exception {
        String csrf = mvc.perform(get("/api/csrf").session(session))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = csrf.split("\"token\":\"")[1].split("\"")[0];
        return mvc.perform(post(API).session(session).header("X-CSRF-TOKEN", token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    long count() {
        return jdbc.queryForObject("select count(*) from hiking_activities where member_id=?", Long.class, ownerId);
    }
    long activityId(String key) {
        return jdbc.queryForObject("select id from hiking_activities where member_id=? and client_request_id=?",
            Long.class, ownerId, key);
    }
    String withoutGps(String body) {
        int key=body.indexOf("\"gpsEvidence\"");
        return body.substring(0,body.lastIndexOf(',',key))+"}";
    }
    long score() { return jdbc.queryForObject("select score from members where id=?",Long.class,ownerId); }
    long events() { return jdbc.queryForObject("select count(*) from hiking_score_events where member_id=?",Long.class,ownerId); }
    long otherMountain() { return jdbc.queryForObject("select min(mountain_id) from courses where mountain_id<>?",Long.class,mountainId); }
    String mountain(String b,long id) { return b.replace("\"mountainId\":"+mountainId,"\"mountainId\":"+id).replace("\"courseId\":"+courseId,"\"courseId\":null"); }
    String day(String b,int day) { return b.replace("2020-01-01", "2020-01-" + String.format("%02d",day)); }
    long journal(String key,boolean visible) throws Exception {
        String token=mvc.perform(get("/api/csrf").session(owner)).andReturn().getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
        mvc.perform(post("/api/journals").session(owner).header("X-CSRF-TOKEN",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"hikingRecordId\":"+activityId(key)+",\"title\":\"Review\",\"content\":\"Test\",\"isPublic\":"+visible+"}"))
            .andExpect(status().isOk());
        return jdbc.queryForObject("select id from hiking_records where hiking_activity_id=?",Long.class,activityId(key));
    }
    void visibility(long id,boolean visible) throws Exception {
        String token=mvc.perform(get("/api/csrf").session(owner)).andReturn().getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
        mvc.perform(patch("/api/journals/"+id).session(owner).header("X-CSRF-TOKEN",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Review\",\"content\":\"Test\",\"isPublic\":"+visible+"}"))
            .andExpect(status().isOk());
    }
    void deleteJournal(long id) throws Exception {
        String token=mvc.perform(get("/api/csrf").session(owner)).andReturn().getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
        mvc.perform(delete("/api/journals/"+id).session(owner).header("X-CSRF-TOKEN",token)).andExpect(status().isNoContent());
    }
    void rebuild() throws Exception {
        String token=mvc.perform(get("/api/csrf").session(owner)).andReturn().getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
        mvc.perform(post(API+"/recalculate-score").session(owner).header("X-CSRF-TOKEN",token)).andExpect(status().isOk());
    }
    @Test void awardsTheFourCasesAndPublicApisReadTheSameTotal() throws Exception {
        for(int day=1;day<=4;day++) {
            String b=day(body(UUID.randomUUID().toString()),day);
            if(day>=3)b=mountain(b,otherMountain());
            if(day==1||day==3)b=b.replace("\"completed\":true","\"completed\":false");
            save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(day==1||day==3?200:100))
                .andExpect(jsonPath("$.scoring.summitPoints").value(day==1||day==3?0:70));
        }
        assertEquals(740,score());
        mvc.perform(get("/api/users/me").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.score").value(740));
        mvc.perform(get("/api/users/"+ownerId+"/profile").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.score").value(740));
        mvc.perform(get("/api/rankings").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.userId == "+ownerId+")].score").value(org.hamcrest.Matchers.hasItem(740)));
    }
    @Test void thresholdAndGpsFailuresDoNotConsumeTheDay() throws Exception {
        String b=body(UUID.randomUUID().toString());
        save(owner,b.replace("1234.5","499.99")).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("BELOW_MINIMUM"));
        save(owner,body(UUID.randomUUID().toString()).replace("2700000","1199999")).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("BELOW_MINIMUM"));
        String old=withoutGps(body(UUID.randomUUID().toString()));
        save(owner,old).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("GPS_REQUIRED"));
        save(owner,body(UUID.randomUUID().toString()).replace("1234.5","500").replace("2700000","1200000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(200));
        assertEquals(270,score()); assertEquals(4,count());
    }
    @Test void invalidGpsIsRejectedWithoutActivityOrScoreMutation() throws Exception {
        String b=body(UUID.randomUUID().toString());
        for(String invalid:List.of(b.replace("\"accuracyMeters\":50","\"accuracyMeters\":50.01"),
            b.replace("\"latitude\":37.65","\"latitude\":91"),b.replace("2020-01-01T01:01:00Z","2020-01-01T00:59:59Z"))) {
            save(owner,invalid).andExpect(status().isBadRequest());
        }
        assertEquals(0,count()); assertEquals(0,events());
    }
    @Test void retriesAreIdempotentAndChangingTheEvidenceConflicts() throws Exception {
        String key=UUID.randomUUID().toString(), b=body(key);
        save(owner,b).andExpect(status().isOk()); long eventCount=events();
        for(int i=0;i<3;i++)save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(activityId(key)));
        assertEquals(1,count());assertEquals(270,score());assertEquals(eventCount,events());
        save(owner,b.replace("\"accuracyMeters\":50","\"accuracyMeters\":49")).andExpect(status().isConflict());
        assertEquals(270,score());assertEquals(eventCount,events());
    }
    @Test void differentUuidsCannotAwardMoreThanOneHikePerDayAndDoNotConsumeAnotherMountainsFirstVisit() throws Exception {
        save(owner,body(UUID.randomUUID().toString())).andExpect(status().isOk());
        save(owner,mountain(body(UUID.randomUUID().toString()),otherMountain())).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("DAILY_LIMIT"));
        save(owner,body(UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("DAILY_LIMIT"));
        assertEquals(270,score());assertEquals(3,count());
        save(owner,day(mountain(body(UUID.randomUUID().toString()),otherMountain()),2)).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(200));
        assertEquals(540,score());
    }
    @Test void lateUploadRebuildsTheDailyWinnerAndLeavesAuditEvents() throws Exception {
        String late=body(UUID.randomUUID().toString()).replace("T01:00:00Z","T02:00:00Z").replace("T01:01:00Z","T02:01:00Z");
        save(owner,late).andExpect(status().isOk());assertEquals(270,score());
        String early=body(UUID.randomUUID().toString()).replace("\"completed\":true","\"completed\":false");
        save(owner,early).andExpect(status().isOk());assertEquals(200,score());
        assertEquals(1,jdbc.queryForObject("select count(*) from hiking_daily_certifications where member_id=?",Integer.class,ownerId));
        assertTrue(jdbc.queryForObject("select count(*) from hiking_score_events where member_id=? and points_delta<0",Integer.class,ownerId)>0);
        long n=events();rebuild();rebuild();assertEquals(n,events());assertEquals(200,score());
    }
    @Test void publicBonusIsOnePerMountainAndSupportsKeepRevokeRestoreAndDelete() throws Exception {
        String one=UUID.randomUUID().toString(),two=UUID.randomUUID().toString();
        save(owner,body(one)).andExpect(status().isOk());save(owner,day(body(two),2)).andExpect(status().isOk());assertEquals(440,score());
        long j1=journal(one,false);assertEquals(440,score());visibility(j1,true);assertEquals(490,score());
        long j2=journal(two,true);assertEquals(490,score());visibility(j1,false);assertEquals(490,score());
        visibility(j2,false);assertEquals(440,score());visibility(j1,true);assertEquals(490,score());
        visibility(j1,true);assertEquals(490,score());deleteJournal(j1);assertEquals(440,score());
        visibility(j2,true);assertEquals(490,score());deleteJournal(j2);assertEquals(440,score());
        assertEquals(1,jdbc.queryForObject("select count(*) from hiking_public_bonuses where member_id=?",Integer.class,ownerId));
    }
    @Test void separatePublicBonusPolicyDoesNotMultiplyByDailyActivitiesAndExcludesUnlinkedLegacyJournals() throws Exception {
        String one=UUID.randomUUID().toString(),two=UUID.randomUUID().toString();
        save(owner,body(one)).andExpect(status().isOk());save(owner,mountain(body(two),otherMountain())).andExpect(status().isOk());
        journal(two,true);assertEquals(320,score()); // publication is a separate per-mountain policy
        jdbc.update("insert into hiking_records(member_id,mountain_name,title,content,hiking_date,is_public) values(?,?,?,?,?,true)",ownerId,"legacy","legacy","body",java.sql.Date.valueOf("2019-01-01"));
        rebuild();assertEquals(320,score());
    }
    @Test void legacyActivityIsNotGrantedInventedGpsAndExistingPublicBonusCanBeRebuilt() throws Exception {
        String key=UUID.randomUUID().toString(),b=withoutGps(body(key));
        save(owner,b).andExpect(status().isOk());assertEquals(0,score());journal(key,true);assertEquals(50,score());
        jdbc.update("update members set score=9999 where id=?",ownerId);rebuild();assertEquals(50,score());long n=events();rebuild();assertEquals(n,events());
    }
    @Test void activityScoreLedgerAndMemberScoreRollBackTogether() {
        TestTransaction.flagForCommit();TestTransaction.end();
        var principal=new OAuthPrincipal(List.of(),Map.of("sub",identity),"sub",info(identity));
        var request=new HikingActivityRequest(mountainId,courseId,Instant.parse("2020-01-01T01:00:00Z"),Instant.parse("2020-01-01T03:00:00Z"),500.0,1200000L,true,UUID.randomUUID().toString(),
            new GpsEvidence(37.65,126.97,50.0,Instant.parse("2020-01-01T01:01:00Z")));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            activityService.create(principal,request);throw new IllegalStateException("forced rollback after activity and score writes");
        }));
        assertEquals(0,count());assertEquals(0,events());
        assertNull(jdbc.queryForObject("select score from members where id=?",Long.class,ownerId));
        TestTransaction.start();
    }
    @Test void concurrentActivitiesAndJournalChangesAreSerializedPerMember() throws Exception {
        TestTransaction.flagForCommit();TestTransaction.end();
        // Establish the shared session's token before concurrent first access. Otherwise
        // two deferred CSRF token generators can overwrite each other and return 403
        // before either request reaches the activity/member-lock code under test.
        // All subsequent writes still use the real security filter and CSRF token.
        mvc.perform(get("/api/csrf").session(owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        var pool=Executors.newFixedThreadPool(2);
        String one=UUID.randomUUID().toString(),two=UUID.randomUUID().toString();
        try {
            var gate=new CountDownLatch(1);
            var f1=pool.submit(()->{gate.await();save(owner,body(one)).andExpect(status().isOk());return null;});
            var f2=pool.submit(()->{gate.await();save(owner,body(two)).andExpect(status().isOk());return null;});
            gate.countDown();f1.get(30,TimeUnit.SECONDS);f2.get(30,TimeUnit.SECONDS);
            assertEquals(2,count());assertEquals(270,score());
            long j1=journal(one,false),j2=journal(two,false);
            var p1=pool.submit(()->{visibility(j1,true);return null;});var p2=pool.submit(()->{visibility(j2,true);return null;});
            p1.get(30,TimeUnit.SECONDS);p2.get(30,TimeUnit.SECONDS);assertEquals(320,score());
            var r1=pool.submit(()->{visibility(j1,false);return null;});var r2=pool.submit(()->{visibility(j2,false);return null;});
            r1.get(30,TimeUnit.SECONDS);r2.get(30,TimeUnit.SECONDS);assertEquals(270,score());
        } finally { pool.shutdownNow();TestTransaction.start(); }
    }
}
