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
        properties.add("spring.datasource.password", () -> System.getProperty("topeak.postgres.test-password", ""));
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    MockMvc mvc;
    Client owner;
    long memberId;
    String providerId;

    record Client(MockHttpSession session, String csrf) {}

    @BeforeEach
    void setup() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).startsWith("uuid_idempotency_regression"));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
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
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", name), "sub", info);
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
            + "\"distanceMeters\":2000,\"elapsedMs\":3600000,\"completed\":true,\"clientRequestId\":\"" + key + "\"}";
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
        String first = saved(owner, body(key));
        jdbc.update("update hiking_activities set client_request_id=? where member_id=?", upper, memberId);
        assertEquals(first, saved(owner, body(key)));
        assertEquals(1, count(key));
        assertEquals(upper, jdbc.queryForObject("select client_request_id from hiking_activities where member_id=?", String.class, memberId));
    }

    @Test void legacyCaseCollisionIsRejectedWithoutChangingRows() throws Exception {
        String key = UUID.randomUUID().toString();
        saved(owner, body(key));
        jdbc.update("insert into hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id) "
            + "select member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,upper(client_request_id) from hiking_activities where member_id=?", memberId);
        send(owner, body(key)).andExpect(status().isConflict());
        assertEquals(2, count(key));
    }

    @Test void nanosecondsAndRoundingBoundariesMatchPersistedResponse() throws Exception {
        for (String fraction : List.of("123456499", "123456500", "123457500", "123456789", "999999500")) {
            String key = UUID.randomUUID().toString();
            String body = body(key).replace("00Z", "00." + fraction + "Z");
            String first = saved(owner, body);
            assertEquals(first, saved(owner, body), fraction);
            long nanos = Long.parseLong(fraction);
            Instant expected = Instant.parse("2020-01-01T01:00:00Z").plusNanos(((nanos + 500) / 1000) * 1000);
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
        String body = body(key).replace("00Z", "00.123456789Z");
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
        } finally {
            gate.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
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
            "insert into hiking_activities(member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id) "
            + "select member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id from hiking_activities where member_id=?", memberId));
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
}
