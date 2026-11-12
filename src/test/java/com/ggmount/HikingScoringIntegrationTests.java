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

/** 격리된 H2에서 실제 보안 필터·서비스·DB의 롤백과 동시 쓰기를 검증한다.
 * 합성 GPS 입력을 사용하므로 실제 이동이나 PostgreSQL의 동시성 동작을 증명하지 않는다.
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
    @Autowired jakarta.persistence.EntityManager entityManager;
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
        // 정상 판정이 DB 좌표와 대조되도록 격리된 테스트 DB의 정상 위치를 고정한다.
        jdbc.update("update mountains set lat=37.65,lng=126.97 where id=? or id=?", mountainId, otherMountain());
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
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", id), "sub", info(id), members.findByProviderAndProviderId("google",id).orElseThrow().getId());
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }
    String offSummit(String b) { return b.replace("\"lng\":126.97", "\"lng\":126.0"); }
    String body(String key) { return body(key, 1234.5); }
    String body(String key, double distance) {
        double endLat = 37.65 + Math.toDegrees(distance / 6_371_000);
        return """
            {"mountainId":%d,"courseId":%d,"startedAt":"2020-01-01T01:00:00Z",
             "endedAt":"2020-01-01T03:00:00Z","distanceMeters":%s,
             "elapsedMs":2700000,"completed":true,"clientRequestId":"%s",
             "gpsSegments":[[{"lat":37.65,"lng":126.97,"accuracyMeters":50,"measuredAt":"2020-01-01T01:01:00Z"},{"lat":%s,"lng":126.97,"accuracyMeters":50,"measuredAt":"2020-01-01T01:10:00Z"}]],
             "gpsEvidence":{"latitude":37.65,"longitude":126.97,"accuracyMeters":50,"recordedAt":"2020-01-01T01:01:00Z"}}
            """.formatted(mountainId, courseId, distance, key, endLat);
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
        return (body.substring(0,body.lastIndexOf(',',key))+"}").replaceAll("\"gpsSegments\":\\[\\[.*?\\]\\]", "\"gpsSegments\":[]");
    }
    @Test void sixPolicyScenariosUseServerRouteProof() throws Exception {
        double[] meters = {200,700,800,250,600,300};
        long[] elapsed = {300000,1500000,1800000,480000,600000,1500000};
        boolean[] summit = {false,false,true,true,false,false};
        for (int i=0;i<6;i++) {
            String b = body(UUID.randomUUID().toString(), meters[i]).replace("2700000", Long.toString(elapsed[i]));
            if (!summit[i]) b = offSummit(b);
            save(owner, day(b,i+1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(summit[i]))
                .andExpect(jsonPath("$.scoring.status").value(i==1||i==2||i==3 ? "CERTIFIED":"BELOW_MINIMUM"))
                .andExpect(jsonPath("$.scoring.summitPoints").value(summit[i]?70:0));
        }
        assertEquals(3, jdbc.queryForObject("select count(*) from hiking_activity_scores where member_id=? and status='CERTIFIED'",Integer.class,ownerId));
        assertEquals(6,count());
    }
    @Test void shortSummitUsesServerCompletionEvenWhenClientExpectedFalseAndRetriesDoNotPayAgain() throws Exception {
        String b = body(UUID.randomUUID().toString(),250).replace("2700000","480000").replace("\"completed\":true","\"completed\":false");
        save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.scoring.status").value("CERTIFIED")).andExpect(jsonPath("$.scoring.summitPoints").value(70));
        assertEquals(270,score()); long before = events();
        save(owner,b).andExpect(status().isOk()); assertEquals(270,score()); assertEquals(before,events()); assertEquals(1,count());
    }
    @Test void shortForgedCompletionWithoutSummitRouteDoesNotCertify() throws Exception {
        String b = offSummit(body(UUID.randomUUID().toString(),250).replace("2700000","480000"));
        save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false))
            .andExpect(jsonPath("$.scoring.status").value("BELOW_MINIMUM"));
        assertEquals(0,score());
    }
    @Test void oldPolicyAwardsAndDailyLedgerRemainUnchangedAfterNewCertification() throws Exception {
        String first=UUID.randomUUID().toString();
        save(owner,body(first)).andExpect(status().isOk());
        long id=activityId(first);
        jdbc.update("update hiking_activity_scores set policy_version='visit-500m-20m-daily1-v1' where activity_id=?",id);
        jdbc.update("insert into hiking_daily_certifications(member_id,certification_date,activity_id) values(?,?,?)",ownerId,java.sql.Date.valueOf("2020-01-01"),id);
        save(owner,body(UUID.randomUUID().toString(),250).replace("2700000","480000").replace("T03:00:00Z","T03:01:00Z"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(100));
        rebuild();
        assertEquals(440,score());
        assertEquals("visit-500m-20m-daily1-v1",jdbc.queryForObject("select policy_version from hiking_activity_scores where activity_id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("select count(*) from hiking_daily_certifications where member_id=?",Integer.class,ownerId));
    }
    @Test void historicalBelowMinimumIsNotAutomaticallyRecertified() throws Exception {
        String first=UUID.randomUUID().toString();
        save(owner,body(first,250).replace("2700000","480000")).andExpect(status().isOk());
        long id=activityId(first);
        jdbc.update("update hiking_activity_scores set status='BELOW_MINIMUM',visit_points=0,summit_points=0,policy_version='visit-500m-20m-daily1-v1' where activity_id=?",id);
        save(owner,body(UUID.randomUUID().toString(),250).replace("2700000","480000").replace("T03:00:00Z","T03:01:00Z"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(200));
        rebuild();
        assertEquals("BELOW_MINIMUM",jdbc.queryForObject("select status from hiking_activity_scores where activity_id=?",String.class,id));
        assertEquals(270,score());
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
            if(day==1||day==3)b=b.replace("\"completed\":true","\"completed\":false").replace("\"lng\":126.97","\"lng\":126.0");
            save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(day==1||day==3?200:100))
                .andExpect(jsonPath("$.scoring.summitPoints").value(day==1||day==3?0:70));
        }
        assertEquals(740,score());
        mvc.perform(get("/api/users/me").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.score").value(740));
        mvc.perform(get("/api/users/"+ownerId+"/profile").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.score").value(740));
        mvc.perform(get("/api/rankings").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.userId == "+ownerId+")].score").value(org.hamcrest.Matchers.hasItem(740)));
    }
    @Test void thresholdAndGpsFailuresDoNotConsumeFirstVisit() throws Exception {
        String b=body(UUID.randomUUID().toString());
        save(owner,offSummit(body(UUID.randomUUID().toString(),499.99))).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("BELOW_MINIMUM"));
        save(owner,offSummit(body(UUID.randomUUID().toString()).replace("T03:00:00Z","T03:02:00Z").replace("2700000","1199999"))).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("BELOW_MINIMUM"));
        String old=withoutGps(body(UUID.randomUUID().toString()).replace("T03:00:00Z","T03:03:00Z"));
        save(owner,old).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("GPS_REQUIRED"));
        save(owner,body(UUID.randomUUID().toString(),500).replace("T03:00:00Z","T03:01:00Z").replace("2700000","1200000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(200));
        assertEquals(270,score()); assertEquals(4,count());
    }
    @Test void exaggeratedDistanceIsRejectedWithoutActivityOrScoreMutation() throws Exception {
        save(owner,body(UUID.randomUUID().toString()).replace("1234.5", "9999"))
            .andExpect(status().isBadRequest());
        assertEquals(0,count()); assertEquals(0,events());
    }
    @Test void savedV1FingerprintAllowsOnlyItsExactRetryNotAnInflatedNewRequest() throws Exception {
        String key = UUID.randomUUID().toString(), b = body(key);
        save(owner,b).andExpect(status().isOk());
        var old = new HikingActivityRequest(mountainId,courseId,Instant.parse("2020-01-01T01:00:00Z"),
            Instant.parse("2020-01-01T03:00:00Z"),9999.0,2700000L,true,key,
            new GpsEvidence(37.65,126.97,50.0,Instant.parse("2020-01-01T01:01:00Z")),
            List.of(List.of(new com.ggmount.hiking.dto.GpsPointRequest(37.65,126.97,Instant.parse("2020-01-01T01:01:00Z"),50.0),
                new com.ggmount.hiking.dto.GpsPointRequest(37.65+Math.toDegrees(1234.5/6_371_000),126.97,Instant.parse("2020-01-01T01:10:00Z"),50.0))));
        jdbc.update("update hiking_activities set gps_validation_version=1,distance_meters=9999,request_fingerprint=? where id=?",
            com.ggmount.hiking.service.HikingRequestFingerprint.of(old),activityId(key));
        entityManager.clear(); // 직접 심은 구버전 행을 JPA 캐시 대신 DB에서 다시 읽는다.
        String inflated = b.replace("1234.5","9999");
        save(owner,inflated).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(activityId(key)));
        save(owner,b).andExpect(status().isConflict());
        save(owner,day(inflated.replace(key,UUID.randomUUID().toString()),2)).andExpect(status().isBadRequest());
        assertEquals(1,count());
    }
    @Test void insufficientEvidenceIsSavedWithoutVisitPointsAndExactOldVersionRetriesStillWork() throws Exception {
        String key = UUID.randomUUID().toString();
        String b = offSummit(body(key)).replaceAll(",\\{\"lat\":[^}]+\"measuredAt\":\"2020-01-01T01:10:00Z\"}", "");
        save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.distanceMeters").value(1234.5))
            .andExpect(jsonPath("$.scoring.status").value("GPS_REQUIRED"));
        assertEquals(0,score());
        long id = activityId(key);
        jdbc.update("update hiking_activities set gps_validation_version=1 where id=?",id);
        save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        save(owner,b.replace("1234.5","1235.5")).andExpect(status().isConflict());
        String newKey = UUID.randomUUID().toString();
        save(owner,day(b.replace(key,newKey),2)).andExpect(status().isOk())
            .andExpect(jsonPath("$.gpsValidationVersion").value(1))
            .andExpect(jsonPath("$.scoring.status").value("GPS_REQUIRED"));
        assertEquals(0,score()); assertEquals(2,count());
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
    @Test void differentActivitiesAreCertifiedOnTheSameDayWithoutDuplicateSessionAwards() throws Exception {
        save(owner,body(UUID.randomUUID().toString())).andExpect(status().isOk());
        save(owner,mountain(body(UUID.randomUUID().toString()),otherMountain())).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.status").value("CERTIFIED"));
        save(owner,body(UUID.randomUUID().toString())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("duplicate_hiking_activity"));
        assertEquals(540,score());assertEquals(2,count());
        save(owner,day(mountain(body(UUID.randomUUID().toString()),otherMountain()),2)).andExpect(status().isOk()).andExpect(jsonPath("$.scoring.visitPoints").value(100));
        assertEquals(710,score());
    }
    @Test void lateUploadReordersFirstVisitWithoutRemovingCertification() throws Exception {
        String late=body(UUID.randomUUID().toString()).replace("T01:00:00Z","T02:00:00Z").replace("T01:01:00Z","T02:01:00Z").replace("T01:10:00Z","T02:10:00Z");
        save(owner,late).andExpect(status().isOk());assertEquals(270,score());
        String early=body(UUID.randomUUID().toString()).replace("\"completed\":true","\"completed\":false").replace("\"lng\":126.97","\"lng\":126.0");
        save(owner,early).andExpect(status().isOk());assertEquals(370,score());
        assertEquals(0,jdbc.queryForObject("select count(*) from hiking_daily_certifications where member_id=?",Integer.class,ownerId));
        assertTrue(jdbc.queryForObject("select count(*) from hiking_score_events where member_id=? and points_delta<0",Integer.class,ownerId)>0);
        long n=events();rebuild();rebuild();assertEquals(n,events());assertEquals(370,score());
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
        journal(two,true);assertEquals(590,score()); // 공개 보너스는 활동 인증과 별개로 산별 적용된다.
        jdbc.update("insert into hiking_records(member_id,mountain_name,title,content,hiking_date,is_public) values(?,?,?,?,?,true)",ownerId,"legacy","legacy","body",java.sql.Date.valueOf("2019-01-01"));
        rebuild();assertEquals(590,score());
    }
    @Test void legacyActivityIsNotGrantedInventedGpsAndExistingPublicBonusCanBeRebuilt() throws Exception {
        String key=UUID.randomUUID().toString(),b=withoutGps(body(key));
        save(owner,b).andExpect(status().isOk());assertEquals(0,score());journal(key,true);assertEquals(50,score());
        jdbc.update("update members set score=9999 where id=?",ownerId);rebuild();assertEquals(50,score());long n=events();rebuild();assertEquals(n,events());
    }
    @Test void activityScoreLedgerAndMemberScoreRollBackTogether() {
        TestTransaction.flagForCommit();TestTransaction.end();
        var principal=new OAuthPrincipal(List.of(),Map.of("sub",identity),"sub",info(identity),ownerId);
        var request=new HikingActivityRequest(mountainId,courseId,Instant.parse("2020-01-01T01:00:00Z"),Instant.parse("2020-01-01T03:00:00Z"),500.0,1200000L,true,UUID.randomUUID().toString(),
            new GpsEvidence(37.65,126.97,50.0,Instant.parse("2020-01-01T01:01:00Z")),
            List.of(List.of(new com.ggmount.hiking.dto.GpsPointRequest(37.65,126.97,Instant.parse("2020-01-01T01:01:00Z"),50.0))));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            activityService.create(principal,request);throw new IllegalStateException("forced rollback after activity and score writes");
        }));
        assertEquals(0,count());assertEquals(0,events());
        assertNull(jdbc.queryForObject("select score from members where id=?",Long.class,ownerId));
        TestTransaction.start();
    }
    @Test void concurrentActivitiesAndJournalChangesAreSerializedPerMember() throws Exception {
        TestTransaction.flagForCommit();TestTransaction.end();
        // 공유 세션의 지연 생성 CSRF 토큰이 서로 덮어써 잠금 검증 전에 차단되지 않도록 미리 발급한다.
        // 이후 쓰기 요청도 실제 보안 필터와 CSRF 검증을 거친다.
        mvc.perform(get("/api/csrf").session(owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        var pool=Executors.newFixedThreadPool(2);
        String one=UUID.randomUUID().toString(),two=UUID.randomUUID().toString();
        try {
            var gate=new CountDownLatch(1);
            var f1=pool.submit(()->{gate.await();save(owner,body(one)).andExpect(status().isOk());return null;});
            var f2=pool.submit(()->{gate.await();save(owner,body(two).replace("T03:00:00Z","T03:01:00Z")).andExpect(status().isOk());return null;});
            gate.countDown();f1.get(30,TimeUnit.SECONDS);f2.get(30,TimeUnit.SECONDS);
            assertEquals(2,count());assertEquals(440,score());
            long j1=journal(one,false),j2=journal(two,false);
            var p1=pool.submit(()->{visibility(j1,true);return null;});var p2=pool.submit(()->{visibility(j2,true);return null;});
            p1.get(30,TimeUnit.SECONDS);p2.get(30,TimeUnit.SECONDS);assertEquals(490,score());
            var r1=pool.submit(()->{visibility(j1,false);return null;});var r2=pool.submit(()->{visibility(j2,false);return null;});
            r1.get(30,TimeUnit.SECONDS);r2.get(30,TimeUnit.SECONDS);assertEquals(440,score());
        } finally { pool.shutdownNow();TestTransaction.start(); }
    }
    @Test void mergedServerCompletionOverridesClientExpectationBeforeAwardingSummitPoints() throws Exception {
        String b=body(UUID.randomUUID().toString()).replace("\"lng\":126.97","\"lng\":126.0");
        save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false))
            .andExpect(jsonPath("$.gpsValidationVersion").value(1))
            .andExpect(jsonPath("$.scoring.visitPoints").value(200))
            .andExpect(jsonPath("$.scoring.summitPoints").value(0));
        assertEquals(200,score());
    }
    @Test void mergedRouteProvenSummitAwardsSeventyEvenWhenClientExpectedFalse() throws Exception {
        save(owner,body(UUID.randomUUID().toString()).replace("\"completed\":true","\"completed\":false"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.scoring.summitPoints").value(70));
        assertEquals(270,score());
    }
    @Test void mergedOptionalSampleCannotReplaceRequiredNewRoute() throws Exception {
        String b=body(UUID.randomUUID().toString()).replaceAll("\"gpsSegments\":\\[\\[.*?\\]\\],", "");
        save(owner,b).andExpect(status().isBadRequest());assertEquals(0,count());assertEquals(0,events());
    }
    @Test void mergedEmptyRouteWithValidOptionalSampleCannotEarnActivityPoints() throws Exception {
        String b=body(UUID.randomUUID().toString()).replaceAll("\"gpsSegments\":\\[\\[.*?\\]\\]", "\"gpsSegments\":[]");
        save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false))
            .andExpect(jsonPath("$.scoring.status").value("GPS_REQUIRED"));
        assertEquals(0,score());
    }
    @Test void mergedLeaderRouteOnlyRequestsStillReceiveScoringWithoutInventingAnOptionalSample() throws Exception {
        String b=body(UUID.randomUUID().toString());int k=b.indexOf("\"gpsEvidence\"");
        b=b.substring(0,b.lastIndexOf(',',k))+"}";
        save(owner,b).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.scoring.visitPoints").value(200))
            .andExpect(jsonPath("$.scoring.summitPoints").value(70));
        assertEquals(270,score());
    }
    @Test void mergedInvalidRouteCannotHideBehindAValidScoringSample() throws Exception {
        save(owner,body(UUID.randomUUID().toString()).replace("\"lat\":37.65","\"lat\":91"))
            .andExpect(status().isBadRequest());assertEquals(0,count());assertEquals(0,events());
    }
    @Test void mergedUppercaseUuidRetryReturnsOneScoredActivityWithoutNewEvents() throws Exception {
        String key=UUID.randomUUID().toString(),b=body(key);
        save(owner,b).andExpect(status().isOk());long n=events();
        save(owner,b.replace(key,key.toUpperCase(java.util.Locale.ROOT))).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(activityId(key)));
        assertEquals(1,count());assertEquals(270,score());assertEquals(n,events());
    }
    @Test void mergedPublicJournalDeletionKeepsServerVerifiedActivityAndItsTwoScoreComponents() throws Exception {
        String key=UUID.randomUUID().toString();save(owner,body(key)).andExpect(status().isOk());
        long id=activityId(key),j=journal(key,true);assertEquals(320,score());deleteJournal(j);
        assertEquals(270,score());assertEquals(1,count());
        mvc.perform(get(API+"/"+id).session(owner)).andExpect(status().isOk())
            .andExpect(jsonPath("$.completed").value(true)).andExpect(jsonPath("$.journalId").isEmpty())
            .andExpect(jsonPath("$.gpsValidationVersion").value(1))
            .andExpect(jsonPath("$.scoring.visitPoints").value(200))
            .andExpect(jsonPath("$.scoring.summitPoints").value(70));
    }

}
