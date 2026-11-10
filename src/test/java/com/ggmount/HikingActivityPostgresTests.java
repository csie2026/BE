package com.ggmount;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.ggmount.hiking.controller.HikingActivityBodyLimitFilter;
import com.ggmount.hiking.repository.HikingActivityRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.OAuthMemberService;
import com.ggmount.hiking.service.HikingActivityService;
import com.ggmount.hiking.dto.HikingActivityRequest;
import com.ggmount.hiking.dto.GpsPointRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt in only with a freshly created, dedicated local PostgreSQL test database.
 * mvn -Dtest=HikingActivityPostgresTests
 * -Dtopeak.postgres.test-url=jdbc:postgresql://127.0.0.1:55439/uuid_idempotency_regression
 * -Dtopeak.postgres.test-user=uuid_test_admin test
 * No outer @Transactional: requests must commit and reload through separate transactions.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=uuid-regression",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.security.oauth2.client.registration.google.client-id=probe",
    "spring.security.oauth2.client.registration.google.client-secret=probe",
    "spring.security.oauth2.client.registration.kakao.client-id=probe",
    "spring.security.oauth2.client.registration.kakao.client-secret=probe"
})
@EnabledIfSystemProperty(named = "topeak.postgres.test-url", matches = ".+")
class HikingActivityPostgresTests {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getProperty("topeak.postgres.test-url");
        // Validate before context startup / Flyway, not after potentially writing to a wrong DB.
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/uuid_idempotency_regression(?:_[a-z0-9]+)?"))
            throw new IllegalArgumentException("A dedicated local uuid_idempotency_regression DB is required");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getProperty("topeak.postgres.test-user", "uuid_test_admin"));
        properties.add("spring.datasource.password", () -> System.getProperty("topeak.postgres.test-password",
            System.getenv().getOrDefault("TOPEAK_TEST_PASSWORD", "")));
        // Flyway must use the same guarded target even if external configuration
        // defines a separate migration connection. Never fall back to a dev DB.
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.url", () -> url);
        properties.add("spring.flyway.user", () -> System.getProperty("topeak.postgres.test-user", "uuid_test_admin"));
        properties.add("spring.flyway.password", () -> System.getProperty("topeak.postgres.test-password",
            System.getenv().getOrDefault("TOPEAK_TEST_PASSWORD", "")));
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.flyway.clean-disabled", () -> "true");
        properties.add("app.scoring.rebuild-on-start", () -> "false");
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired HikingActivityBodyLimitFilter bodyLimit;
    @MockitoSpyBean HikingActivityRepository activities;
    @MockitoSpyBean MemberRepository memberRepository;
    @Autowired OAuthMemberService oauthMembers;
    @Autowired HikingActivityService activityService;
    @Autowired com.ggmount.hiking.service.HikingRecordService journalService;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    Client owner;
    long memberId;
    String providerId;

    record Client(MockHttpSession session, String csrf) {}

    @BeforeEach
    void setup() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("uuid_idempotency_regression"));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(bodyLimit, filters).build();
        providerId = "uuid-regression-" + UUID.randomUUID();
        owner = member(providerId);
        memberId = jdbc.queryForObject("select id from members where provider_id=?", Long.class, providerId);
    }

    Client member(String name) throws Exception {
        jdbc.update("insert into members(provider,provider_id,nickname,profile_completed) values('google',?,'UUID test',true)", name);
        return session(name);
    }

    Client session(String name) throws Exception {
        var info = new OAuthUserInfo("google", name, null, "Probe", null);
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", name), "sub", info, jdbc.queryForObject("select id from members where provider='google' and provider_id=?",Long.class,name));
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String json = mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Client(session, json.split("\"token\":\"")[1].split("\"")[0]);
    }

    String body(String key) {
        return "{\"mountainId\":1,\"startedAt\":\"2020-01-01T01:00:00Z\",\"endedAt\":\"2020-01-01T02:00:00Z\","
            + "\"distanceMeters\":2000,\"elapsedMs\":3600000,\"completed\":true,\"gpsSegments\":[],\"clientRequestId\":\"" + key + "\"}";
    }

    ResultActions send(Client client, String body) throws Exception {
        return mvc.perform(post("/api/users/me/hiking-records").session(client.session())
            .header("X-CSRF-TOKEN", client.csrf()).contentType("application/json").content(body));
    }

    String saved(Client client, String body) throws Exception {
        return send(client, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    long count(String key) {
        return jdbc.queryForObject("select count(*) from hiking_activities where member_id=? and lower(client_request_id)=lower(?)",
            Long.class, memberId, key);
    }

    void insertLegacy(String key) {
        jdbc.update("insert into hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id,gps_validation_version,request_fingerprint_version) "
            + "values(?,1,'Legacy','2020-01-01T01:00:00Z','2020-01-01T02:00:00Z',2000,3600000,true,?,null,null)", memberId, key);
    }

    String point(double lat, double lng, double accuracy, String time) {
        return "{\"lat\":" + lat + ",\"lng\":" + lng + ",\"accuracyMeters\":" + accuracy + ",\"measuredAt\":\"" + time + "\"}";
    }

    String evidence(String body, String segments) {
        return body.replace("\"gpsSegments\":[]", "\"gpsSegments\":" + segments);
    }

    String summitPoint(double accuracy) {
        return jdbc.queryForObject("select lat,lng from mountains where id=1",
            (rs, i) -> point(rs.getDouble(1), rs.getDouble(2), accuracy, "2020-01-01T01:30:00Z"));
    }

    @Test void serverRecalculatesCompletionAndRetryDoesNotRecalculate() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = evidence(body(key).replace("\"completed\":true", "\"completed\":false"), "[[" + summitPoint(50) + "]]");
        String first = send(owner, body).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.gpsValidationVersion").value(1)).andReturn().getResponse().getContentAsString();
        assertEquals(first, saved(owner, body)); // Expected false is retained in the fingerprint.
        jdbc.update("update hiking_activities set completed=false where member_id=?", memberId);
        send(owner, body).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
        assertEquals(64, jdbc.queryForObject("select length(request_fingerprint) from hiking_activities where member_id=?", Integer.class, memberId));
        assertEquals(1, count(key));
    }

    @Test void emptyNonSummitAndInaccurateEvidenceCannotForgeCompletion() throws Exception {
        String key = UUID.randomUUID().toString();
        send(owner, body(key)).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
        String second = body(UUID.randomUUID().toString()).replace("2020-01-01", "2020-01-02");
        send(owner, evidence(second, "[[" + summitPoint(50.0001).replace("2020-01-01", "2020-01-02") + "]]"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
        String third = body(UUID.randomUUID().toString()).replace("2020-01-01", "2020-01-03");
        send(owner, evidence(third, "[[" + point(0, 0, 1, "2020-01-03T01:30:00Z") + "]]"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
    }

    @Test void thirtyMeterBoundaryUsesDatabaseSummit() throws Exception {
        long mountain = 900_000_000L + memberId;
        jdbc.update("insert into mountains(id,name,region,sigungu_code,sigungu_name,address,height,lat,lng,grid_nx,grid_ny) "
            + "values(?,?,'NORTH','00000','Test','Test',1,0,0,1,1)", mountain, "Boundary " + memberId);
        for (double meters : new double[]{29.999, 30, 30.001}) {
            String body = body(UUID.randomUUID().toString()).replace("\"mountainId\":1", "\"mountainId\":" + mountain)
                .replace("2020-01-01", meters == 29.999 ? "2020-01-01" : meters == 30 ? "2020-01-02" : "2020-01-03");
            String time = meters == 29.999 ? "2020-01-01T01:30:00Z" : meters == 30 ? "2020-01-02T01:30:00Z" : "2020-01-03T01:30:00Z";
            send(owner, evidence(body, "[[" + point(0, Math.toDegrees(meters / 6_371_000), 50, time) + "]]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(meters <= 30));
        }
    }

    @Test void zeroDurationAndDistanceWithOneSummitSampleAreAllowed() throws Exception {
        String body = body(UUID.randomUUID().toString()).replace("02:00:00Z", "01:00:00Z")
            .replace("3600000", "0").replace("2000,", "0,");
        send(owner, evidence(body, "[[" + summitPoint(0).replace("01:30:00Z", "01:00:00Z") + "]]"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
    }

    @Test void newRequestsRequireEvidenceAndValidateEveryPoint() throws Exception {
        String body = body(UUID.randomUUID().toString());
        send(owner, body.replace(",\"gpsSegments\":[]", "")).andExpect(status().isBadRequest());
        send(owner, evidence(body, "null")).andExpect(status().isBadRequest());
        for (String segments : List.of("[null]", "[[null]]", "[[{}]]", "[[[]]]",
            "[[" + point(91, 0, 1, "2020-01-01T01:30:00Z") + "]]",
            "[[" + point(0, 181, 1, "2020-01-01T01:30:00Z") + "]]",
            "[[" + point(0, 0, -1, "2020-01-01T01:30:00Z") + "]]",
            "[[" + point(0, 0, 1, "2020-01-01T00:59:59Z") + "]]",
            "[[" + point(0, 0, 1, "2020-01-01T02:00:01Z") + "]]",
            "[[" + summitPoint(1) + "],[" + point(0, 0, 1, "2020-01-01T01:00:00Z") + "]]",
            "[[" + summitPoint(1).replace("\"lat\":", "\"lat\":1e400,\"ignored\":") + "]]")) {
            send(owner, evidence(body, segments)).andExpect(status().isBadRequest());
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from hiking_activities where member_id=?", Long.class, memberId));
    }

    @Test void changedEvidenceOrClientExpectationConflictsEvenIfResultIsSame() throws Exception {
        String body = evidence(body(UUID.randomUUID().toString()), "[[" + summitPoint(1) + "]]");
        saved(owner, body);
        send(owner, body.replace("\"accuracyMeters\":1.0", "\"accuracyMeters\":2.0"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("client_request_conflict"));
        send(owner, body.replace("\"completed\":true", "\"completed\":false")).andExpect(status().isConflict());
        send(owner, body.replace("\"gpsSegments\":[[", "\"gpsSegments\":[[],[" )).andExpect(status().isConflict());
        // Equivalent Instant and numeric JSON spellings are canonicalized.
        assertEquals(saved(owner, body), saved(owner, body.replace("01:30:00Z", "01:30:00.000000000Z").replace("1.0,", "1,")));
    }

    @Test void differentUuidForSameNormalizedSessionConflictsIncludingLegacy() throws Exception {
        String key = UUID.randomUUID().toString();
        saved(owner, body(key));
        send(owner, body(UUID.randomUUID().toString()).replace("00Z", "00.000000001Z"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("duplicate_hiking_activity"));
        assertEquals(1L, jdbc.queryForObject("select count(*) from hiking_activities where member_id=?", Long.class, memberId));
    }

    @Test void legacyHistoryKeepsItsOriginalCompletionAndBlocksNewUuid() throws Exception {
        String key = UUID.randomUUID().toString();
        insertLegacy(key);
        send(owner, body(key).replace(",\"gpsSegments\":[]", "")).andExpect(status().isOk())
            .andExpect(jsonPath("$.completed").value(true)).andExpect(jsonPath("$.gpsValidationVersion").isEmpty());
        send(owner, body(UUID.randomUUID().toString())).andExpect(status().isConflict());
        send(owner, body(key).replace("2000,", "2001,")).andExpect(status().isConflict());
    }

    @Test void gpsResourceLimitsDoNotImposeMinimumSamples() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        String p = summitPoint(50);
        send(owner, evidence(body, "[[" + String.join(",", Collections.nCopies(10_001, p)) + "]]"))
            .andExpect(status().isBadRequest());
        send(owner, evidence(body, "[" + String.join(",", Collections.nCopies(10_001, "[]")) + "]"))
            .andExpect(status().isBadRequest());
        send(owner, evidence(body, "[[" + String.join(",", Collections.nCopies(10_000, p)) + "]]"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        assertEquals(1, count(key));
    }

    @Test void declaredLengthBodiesAreBounded() throws Exception {
        String body = body(UUID.randomUUID().toString());
        String exact = body + " ".repeat(HikingActivityBodyLimitFilter.MAX_BYTES - body.length());
        send(owner, exact).andExpect(status().isOk());
        send(owner, exact + " ").andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("hiking_request_too_large"));
    }

    @Test void repeatedRequestHasOneCommittedActivity() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        String first = saved(owner, body);
        for (int i = 0; i < 3; i++) assertEquals(first, saved(owner, body));
        assertEquals(1, count(key));
    }

    @Test void changedValidBodyConflicts() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        saved(owner, body);
        send(owner, body.replace("2000,", "2001,")).andExpect(status().isConflict());
        assertEquals(1, count(key));
    }

    @Test void uuidCaseUsesSameActivityAndStoresLowercase() throws Exception {
        String lower = UUID.randomUUID().toString(), upper = lower.toUpperCase(Locale.ROOT);
        String first = saved(owner, body(upper));
        assertEquals(first, saved(owner, body(lower)));
        assertEquals(1, count(lower));
        assertEquals(lower, jdbc.queryForObject("select client_request_id from hiking_activities where member_id=?", String.class, memberId));
    }

    @Test void legacyUppercaseIsReusedWithoutRewritingIt() throws Exception {
        String key = UUID.randomUUID().toString(), upper = key.toUpperCase(Locale.ROOT);
        insertLegacy(upper);
        String first = saved(owner, body(key).replace(",\"gpsSegments\":[]", ""));
        assertEquals(first, saved(owner, body(key)));
        assertEquals(1, count(key));
        assertEquals(upper, jdbc.queryForObject("select client_request_id from hiking_activities where member_id=?", String.class, memberId));
    }

    @Test void legacyCaseCollisionIsRejectedWithoutChangingRows() throws Exception {
        String key = UUID.randomUUID().toString();
        insertLegacy(key);
        jdbc.update("insert into hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id,gps_validation_version,request_fingerprint_version) "
            + "select member_id,mountain_id,mountain_name,started_at+interval '1 second',ended_at+interval '1 second',distance_meters,elapsed_ms,completed,upper(client_request_id),null,null from hiking_activities where member_id=?", memberId);
        send(owner, body(key)).andExpect(status().isConflict());
        assertEquals(2, count(key));
    }

    @Test void nanosecondsAndRoundingBoundariesMatchPersistedResponse() throws Exception {
        int second = 0;
        for (String fraction : List.of("123456499", "123456500", "123457500", "123456789", "999999500")) {
            String key = UUID.randomUUID().toString();
            String body = body(key).replace("00Z", String.format("%02d.%sZ", second, fraction));
            String first = saved(owner, body);
            assertEquals(first, saved(owner, body), fraction);
            long nanos = Long.parseLong(fraction);
            Instant expected = Instant.parse("2020-01-01T01:00:00Z").plusSeconds(second++).plusNanos(((nanos + 500) / 1000) * 1000);
            Instant stored = jdbc.queryForObject("select started_at from hiking_activities where member_id=? and client_request_id=?",
                (rs, i) -> rs.getTimestamp(1).toInstant(), memberId, key);
            assertEquals(expected, stored, fraction);
            assertTrue(first.contains("\"startedAt\":\"" + expected + "\""), first);
            assertEquals(1, count(key));
        }
    }

    @Test void differentValidTimesStillConflict() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        saved(owner, body);
        // Both time-shifted and one-microsecond-different bodies remain valid.
        send(owner, body.replace("01:00:00Z", "01:00:00.000001Z").replace("02:00:00Z", "02:00:00.000001Z"))
            .andExpect(status().isConflict());
        send(owner, body.replace("02:00:00Z", "02:00:01Z")).andExpect(status().isConflict());
        assertEquals(1, count(key));
    }

    @Test void equivalentTimestampFormattingReusesActivity() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        assertEquals(saved(owner, body), saved(owner, body.replace("00Z", "00.000000Z")));
    }

    @Test void independentConcurrentRequestsSerializeToOneActivity() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = scoredBody(key).replace("00Z", "00.123456789Z");
        var pool = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        var futures = new ArrayList<Future<String>>();
        long waiting = 0;
        try {
            try (var lock = dataSource.getConnection()) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("select id from members where id=? for update")) {
                    statement.setLong(1, memberId);
                    statement.executeQuery().close();
                }
                for (int i = 0; i < 8; i++) {
                    Client client = session(providerId);
                    // Each worker has its own session and transaction, with mixed UUID case.
                    String content = i % 2 == 0 ? body : body.replace(key, key.toUpperCase(Locale.ROOT));
                    futures.add(pool.submit(() -> { gate.await(); return saved(client, content); }));
                }
                gate.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and query like '%members%for update%'", Long.class);
                    if (waiting == 8) break;
                    Thread.sleep(50);
                }
                lock.commit();
            }
            assertEquals(8, waiting, "Eight independent backends must be waiting before releasing the lock");
            String first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(first, future.get(30, TimeUnit.SECONDS));
            assertEquals(1, count(key));
            assertProjection(270);
            assertEquals(1L, jdbc.queryForObject("select count(*) from hiking_activity_scores where member_id=?", Long.class, memberId));
            assertEquals(2L, jdbc.queryForObject("select count(*) from hiking_score_events where member_id=?", Long.class, memberId));
        } finally {
            gate.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test void independentDifferentUuidRequestsHaveExactlyOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        var futures = new ArrayList<Future<Integer>>();
        long waiting = 0;
        try {
            try (var lock = dataSource.getConnection()) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("select id from members where id=? for update")) {
                    statement.setLong(1, memberId);
                    statement.executeQuery().close();
                }
                for (int i = 0; i < 8; i++) {
                    Client client = session(providerId);
                    String content = body(UUID.randomUUID().toString());
                    futures.add(pool.submit(() -> {
                        gate.await();
                        var response = send(client, content).andReturn().getResponse();
                        if (response.getStatus() == 409) assertTrue(response.getContentAsString().contains("duplicate_hiking_activity"));
                        return response.getStatus();
                    }));
                }
                gate.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and query like '%members%for update%'", Long.class);
                    if (waiting == 8) break;
                    Thread.sleep(50);
                }
                lock.commit();
            }
            assertEquals(8, waiting);
            var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(statuses, 200));
            assertEquals(7, Collections.frequency(statuses, 409));
            assertEquals(1L, jdbc.queryForObject("select count(*) from hiking_activities where member_id=?", Long.class, memberId));
        } finally {
            gate.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test void postgresUniqueFailureRollsBackBeforeHttpConflictAndNextRequestWorks() throws Exception {
        saved(owner, body(UUID.randomUUID().toString()));
        // Simulate a stale/missed application precheck; PostgreSQL remains the final guard.
        doReturn(false).when(activities).existsByMemberIdAndMountainIdAndStartedAtAndEndedAt(
            memberId, 1L, Instant.parse("2020-01-01T01:00:00Z"), Instant.parse("2020-01-01T02:00:00Z"));
        send(owner, body(UUID.randomUUID().toString())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("duplicate_hiking_activity"));
        assertEquals(1L, jdbc.queryForObject("select count(*) from hiking_activities where member_id=?", Long.class, memberId));
        saved(owner, body(UUID.randomUUID().toString()).replace("2020-01-01", "2020-01-02"));
        assertEquals(2L, jdbc.queryForObject("select count(*) from hiking_activities where member_id=?", Long.class, memberId));
    }

    @Test void postgresMigrationPreservesLegacyRowsAndFailsOnDuplicateHistory() throws Exception {
        for (boolean duplicate : List.of(false, true)) {
            String schema = "integrity_" + UUID.randomUUID().toString().replace("-", "");
            var flyway = org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
            flyway.target("4").load().migrate();
            jdbc.update("insert into " + schema + ".members(id,provider,provider_id,nickname,profile_completed) values(1,'google','legacy','Legacy',true)");
            String columns = "(member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id)";
            String values = " values(1,1,'Legacy','2020-01-01T01:00:00Z','2020-01-01T02:00:00Z',0,0,true,?)";
            jdbc.update("insert into " + schema + ".hiking_activities" + columns + values, UUID.randomUUID().toString());
            if (duplicate) {
                jdbc.update("insert into " + schema + ".hiking_activities" + columns + values, UUID.randomUUID().toString());
                assertThrows(org.flywaydb.core.api.FlywayException.class,
                    () -> org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate());
                assertEquals(2L, jdbc.queryForObject("select count(*) from " + schema + ".hiking_activities", Long.class));
                assertEquals(0L, jdbc.queryForObject("select count(*) from " + schema + ".flyway_schema_history where script='V7__hiking_activity_integrity.sql' and success", Long.class));
            } else {
                org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
                assertEquals(1L, jdbc.queryForObject("select count(*) from " + schema + ".hiking_activities where completed and gps_validation_version is null and request_fingerprint is null", Long.class));
                assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into " + schema + ".hiking_activities" + columns + values, UUID.randomUUID().toString()));
            }
        }
    }

    @Test void sameUuidIsIndependentAcrossMembers() throws Exception {
        String key = UUID.randomUUID().toString();
        Client other = member("uuid-regression-" + UUID.randomUUID());
        assertNotEquals(saved(owner, body(key)), saved(other, body(key.toUpperCase(Locale.ROOT))));
        assertEquals(1, count(key));
        assertEquals(2L, jdbc.queryForObject("select count(*) from hiking_activities where lower(client_request_id)=?", Long.class, key));
    }

    @Test void flywayAndDatabaseUniqueConstraintAreActive() throws Exception {
        assertTrue(jdbc.queryForObject("select version()", String.class).startsWith("PostgreSQL"));
        assertEquals(1L, jdbc.queryForObject("select count(*) from flyway_schema_history where version='4' and success", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from pg_constraint where conrelid='hiking_activities'::regclass "
            + "and contype='u' and pg_get_constraintdef(oid)='UNIQUE (member_id, client_request_id)'", Long.class));
        String key = UUID.randomUUID().toString();
        saved(owner, body(key));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "insert into hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id,gps_validation_version,request_fingerprint_version,request_fingerprint) "
            + "select member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id,gps_validation_version,request_fingerprint_version,request_fingerprint from hiking_activities where member_id=?", memberId));
        assertEquals(1, count(key));
    }

    @Test void rawTimeAndElapsedValidationIsPreserved() throws Exception {
        String key = UUID.randomUUID().toString(), body = body(key);
        send(owner, body.replace("3600000", "3600001")).andExpect(status().isBadRequest());
        send(owner, body.replace("02:00:00Z", "00:59:59.999999999Z")).andExpect(status().isBadRequest());
        // These reversed instants would round to the same microsecond; raw input must still fail.
        send(owner, body.replace("01:00:00Z", "01:00:00.000000002Z")
            .replace("02:00:00Z", "01:00:00.000000001Z").replace("3600000", "0"))
            .andExpect(status().isBadRequest());
        send(owner, body.replace("2020-01-01", "2999-01-01")).andExpect(status().isBadRequest());
        send(owner, body.replace("2000,", "-1,")).andExpect(status().isBadRequest());
        assertEquals(0, count(key));
        // One millisecond of valid elapsed time remains valid at a rounding boundary.
        String shortBody = body.replace("01:00:00Z", "01:00:00.000000500Z")
            .replace("02:00:00Z", "01:00:00.001000500Z").replace("3600000", "1");
        assertEquals(saved(owner, shortBody), saved(owner, shortBody));
    }

    // All following tests are covered by the existing opt-in class condition and
    // pre-Flyway URL guard. They must never run against an application database.
    long jsonId(String json) {
        return ((Number) org.springframework.boot.json.JsonParserFactory.getJsonParser().parseMap(json).get("id")).longValue();
    }

    String scoredBody(String key) {
        // A real 600m two-point route ending at the seeded summit, rather than a
        // fabricated distance or a change to the existing scoring policy.
        return jdbc.queryForObject("select lat,lng from mountains where id=1", (rs, i) -> {
            double lat = rs.getDouble(1), lng = rs.getDouble(2);
            String first = point(lat - Math.toDegrees(600.0 / 6_371_000), lng, 10, "2020-01-01T01:00:00Z");
            String last = point(lat, lng, 10, "2020-01-01T01:30:00Z");
            return evidence(body(key).replace("2000,", "600,"), "[[" + first + "," + last + "]]");
        });
    }

    long score() {
        return jdbc.queryForObject("select score from members where id=?", Long.class, memberId);
    }

    Map<String, Object> snapshot() {
        return jdbc.queryForMap("select score,"
            + " (select count(*) from hiking_activities where member_id=m.id) as activities,"
            + " (select count(*) from hiking_records where member_id=m.id) as journals,"
            + " (select count(*) from hiking_activity_scores where member_id=m.id) as ledger,"
            + " (select count(*) from hiking_daily_certifications where member_id=m.id) as daily,"
            + " (select coalesce(sum(points),0) from hiking_public_bonuses where member_id=m.id) as bonus,"
            + " (select count(*) from hiking_score_events where member_id=m.id) as events"
            + " from members m where id=?", memberId);
    }

    void assertProjection(long expected) {
        assertEquals(expected, score());
        assertEquals(expected, jdbc.queryForObject("select"
            + " (select coalesce(sum(visit_points+summit_points),0) from hiking_activity_scores where member_id=?)"
            + " + (select coalesce(sum(points),0) from hiking_public_bonuses where member_id=?)", Long.class, memberId, memberId));
        assertEquals(expected, jdbc.queryForObject("select coalesce(sum(points_delta),0) from hiking_score_events where member_id=? and kind='MEMBER_TOTAL'", Long.class, memberId));
    }

    @Test void scoredActivityRetryAndConflictPreserveLedgerAndMemberTotal() throws Exception {
        String key = UUID.randomUUID().toString(), request = scoredBody(key);
        String first = saved(owner, request);
        assertProjection(270);
        assertEquals(1L, jdbc.queryForObject("select count(*) from hiking_activity_scores where member_id=? and status='CERTIFIED' and visit_points=200 and summit_points=70", Long.class, memberId));
        var before = snapshot();
        assertEquals(first, saved(owner, request));
        assertEquals(before, snapshot(), "Identical retry must not duplicate projections or audit events");
        send(owner, request.replace("600,", "600.5,")).andExpect(status().isConflict());
        assertEquals(before, snapshot());
    }

    long journal(long activityId, boolean published) throws Exception {
        return jsonId(mvc.perform(post("/api/journals").session(owner.session()).header("X-CSRF-TOKEN", owner.csrf())
            .contentType("application/json").content("{\"hikingRecordId\":" + activityId
                + ",\"title\":\"Postgres\",\"content\":\"review\",\"isPublic\":" + published + "}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    void visibility(long journalId, boolean published) throws Exception {
        mvc.perform(patch("/api/journals/" + journalId).session(owner.session()).header("X-CSRF-TOKEN", owner.csrf())
            .contentType("application/json").content("{\"title\":\"Updated\",\"content\":\"review\",\"isPublic\":" + published + "}"))
            .andExpect(status().isOk());
    }

    @Test void publicJournalChangesAndDeletionRebuildCommittedScores() throws Exception {
        long activityId = jsonId(saved(owner, scoredBody(UUID.randomUUID().toString())));
        long journalId = journal(activityId, false);
        assertProjection(270);
        visibility(journalId, true); assertProjection(320);
        var published = snapshot();
        var journalBefore = jdbc.queryForMap("select title,content,is_public,hiking_activity_id from hiking_records where id=?", journalId);
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", providerId), "sub",
            new OAuthUserInfo("google", providerId, null, "Probe", null), memberId);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            journalService.update(principal, journalId,
                new com.ggmount.hiking.dto.JournalUpdateRequest("Rollback", "must not persist", false, null, null, null));
            assertProjection(270);
            throw new IllegalStateException("forced rollback after journal and bonus changes");
        }));
        assertEquals(published, snapshot());
        assertEquals(journalBefore, jdbc.queryForMap("select title,content,is_public,hiking_activity_id from hiking_records where id=?", journalId));
        visibility(journalId, true); assertEquals(published, snapshot());
        visibility(journalId, false); assertProjection(270);
        visibility(journalId, true); assertProjection(320);
        mvc.perform(delete("/api/journals/" + journalId).session(owner.session()).header("X-CSRF-TOKEN", owner.csrf()))
            .andExpect(status().isNoContent());
        assertProjection(270);
        assertEquals(1L, jdbc.queryForObject("select count(*) from hiking_activities where id=?", Long.class, activityId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from hiking_records where id=?", Long.class, journalId));
    }

    @Test void forcedExceptionRollsBackActivityLedgerAndScoreAndAllowsNextRequest() throws Exception {
        saved(owner, scoredBody(UUID.randomUUID().toString()));
        var before = snapshot();
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", providerId), "sub",
            new OAuthUserInfo("google", providerId, null, "Probe", null), memberId);
        double[] summit = jdbc.queryForObject("select lat,lng from mountains where id=1", (rs, i) -> new double[] {rs.getDouble(1), rs.getDouble(2)});
        var points = List.of(List.of(
            new GpsPointRequest(summit[0] - Math.toDegrees(600.0 / 6_371_000), summit[1], Instant.parse("2020-01-02T01:00:00Z"), 10.0),
            new GpsPointRequest(summit[0], summit[1], Instant.parse("2020-01-02T01:30:00Z"), 10.0)));
        String key = UUID.randomUUID().toString();
        var request = new HikingActivityRequest(1L, null, Instant.parse("2020-01-02T01:00:00Z"),
            Instant.parse("2020-01-02T02:00:00Z"), 600.0, 3600000L, true, key, null, points);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            activityService.create(principal, request);
            assertEquals(440, score(), "Both activity and score writes happened before injected failure");
            throw new IllegalStateException("forced rollback after score rebuild");
        }));
        assertEquals(before, snapshot());
        assertEquals(0, count(key));
        saved(owner, scoredBody(key).replace("2020-01-01", "2020-01-02"));
        assertProjection(440);
    }

    @Test void postgresUniqueViolationRollsBackWithoutChangingExistingScoreData() throws Exception {
        saved(owner, scoredBody(UUID.randomUUID().toString()));
        var before = snapshot();
        doReturn(false).when(activities).existsByMemberIdAndMountainIdAndStartedAtAndEndedAt(
            memberId, 1L, Instant.parse("2020-01-01T01:00:00Z"), Instant.parse("2020-01-01T02:00:00Z"));
        send(owner, scoredBody(UUID.randomUUID().toString())).andExpect(status().isConflict());
        assertEquals(before, snapshot());
        saved(owner, scoredBody(UUID.randomUUID().toString()).replace("2020-01-01", "2020-01-02"));
        assertProjection(440);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"google", "kakao"})
    void concurrentOAuthFirstLoginsCommitOneMemberAndRecoverAfterUniqueViolation(String provider) throws Exception {
        String identity = "pg-oauth-" + UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        var absent = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            var found = memberRepository.findByProviderAndProviderId(provider, identity);
            if (found.isEmpty()) { absent.incrementAndGet(); barrier.await(10, TimeUnit.SECONDS); }
            return found;
        }).when(memberRepository).lockForLogin(provider, identity);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var info = new OAuthUserInfo(provider, identity, null, null, null);
            var one = pool.submit(() -> oauthMembers.processLogin(info).getId());
            var two = pool.submit(() -> oauthMembers.processLogin(info).getId());
            long id = one.get(30, TimeUnit.SECONDS);
            assertEquals(id, two.get(30, TimeUnit.SECONDS));
            assertEquals(2, absent.get(), "Both transactions must observe the absent row before inserting");
            assertEquals(1L, jdbc.queryForObject("select count(*) from members where provider=? and provider_id=?", Long.class, provider, identity));
            assertEquals(id, oauthMembers.processLogin(info).getId());
            assertEquals(false, jdbc.queryForObject("select profile_completed from members where id=?", Boolean.class, id));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void emptySchemaMigratesThroughLatestVersionWithExpectedConstraints() {
        String schema = "schema_review_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
            .schemas(schema).defaultSchema(schema).cleanDisabled(true).load();
        flyway.migrate();
        flyway.validate();
        assertEquals("8", flyway.info().current().getVersion().toString());
        assertEquals(9L, jdbc.queryForObject("select count(*) from " + schema + ".flyway_schema_history where success and version is not null", Long.class));
        for (String table : List.of("members", "mountains", "courses", "hiking_activities", "hiking_records",
            "member_monthly_goals", "hiking_activity_scores", "hiking_daily_certifications", "hiking_public_bonuses", "hiking_score_events")) {
            assertEquals(1L, jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name=?", Long.class, schema, table));
        }
        assertTrue(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name='hiking_activities' and column_name in ('gps_validation_version','request_fingerprint_version','request_fingerprint')", Long.class, schema) == 3);
        assertEquals("timestamp with time zone", jdbc.queryForObject("select data_type from information_schema.columns where table_schema=? and table_name='hiking_activities' and column_name='started_at'", String.class, schema));
        assertEquals("NO", jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=? and table_name='hiking_activities' and column_name='member_id'", String.class, schema));
        assertEquals("YES", jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=? and table_name='member_monthly_goals' and column_name='target_distance_km'", String.class, schema));
        assertEquals("YES", jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=? and table_name='members' and column_name='joined_at'", String.class, schema));
        var constraints = jdbc.queryForList("select pg_get_constraintdef(c.oid) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname=?", String.class, schema);
        for (String expected : List.of("UNIQUE (provider, provider_id)", "UNIQUE (member_id, client_request_id)",
            "UNIQUE (member_id, mountain_id, started_at, ended_at)", "UNIQUE (hiking_activity_id)"))
            assertTrue(constraints.contains(expected), expected);
        for (String table : List.of("hiking_activities", "hiking_records", "hiking_activity_scores", "hiking_daily_certifications"))
            assertTrue(jdbc.queryForObject("select count(*) from pg_constraint c join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace where n.nspname=? and t.relname=? and c.contype='f'", Long.class, schema, table) > 0, table);
        long member = jdbc.queryForObject("insert into " + schema + ".members(provider,provider_id,nickname,profile_completed) values('google','constraint-probe','Probe',true) returning id", Long.class);
        assertDatabaseViolation("23505", () -> jdbc.update("insert into " + schema + ".members(provider,provider_id,profile_completed) values('google','constraint-probe',false)"));
        String insert = "insert into " + schema + ".hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id,gps_validation_version,request_fingerprint_version) values(?,1,'Probe','2020-01-01T01:00:00Z','2020-01-01T02:00:00Z',?,3600000,false,?,null,null)";
        assertDatabaseViolation("23503", () -> jdbc.update(insert, -1L, 0.0, UUID.randomUUID().toString()));
        assertDatabaseViolation("23514", () -> jdbc.update(insert, member, -1.0, UUID.randomUUID().toString()));
        jdbc.update(insert, member, 0.0, UUID.randomUUID().toString());
        assertDatabaseViolation("23505", () -> jdbc.update(insert, member, 0.0, UUID.randomUUID().toString()));
        assertDatabaseViolation("23503", () -> jdbc.update("insert into " + schema + ".hiking_records(member_id,mountain_name,title,content,hiking_date,is_public,hiking_activity_id) values(?,'Probe','Probe','','2020-01-01',false,-1)", member));
        assertDatabaseViolation("23514", () -> jdbc.update("insert into " + schema + ".member_monthly_goals(member_id,goal_month,target_distance_km,target_hiking_count) values(?,'2020-01-01',null,null)", member));
        assertEquals(1L, jdbc.queryForObject("select count(*) from " + schema + ".hiking_activities", Long.class));
        // A second migrate must be a no-op; existing schema/data is never reset.
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    void assertDatabaseViolation(String state, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(DataIntegrityViolationException.class, operation);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) { assertEquals(state, sql.getSQLState()); return; }
        }
        fail("Expected PostgreSQL SQLSTATE " + state);
    }
}
