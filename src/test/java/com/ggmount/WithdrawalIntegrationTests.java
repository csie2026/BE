package com.ggmount;

import com.ggmount.global.auth.oauth.*;
import com.ggmount.member.dto.WithdrawalRequest;
import com.ggmount.member.service.OAuthMemberService;
import com.ggmount.member.service.WithdrawalService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 전용 메모리 H2에서 보안 필터를 거친 모의 HTTP 요청의 실제 커밋을 검사하며, 전체를 감싸는 롤백 트랜잭션은 사용하지 않는다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:withdrawal_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:withdrawal_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password="
})
class WithdrawalIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired OAuthMemberService oauth;
    @Autowired WithdrawalService withdrawals;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    MockMvc mvc;
    record Client(long id, OAuthPrincipal principal, MockHttpSession session, String csrf) {}
    @BeforeEach void setup() throws Exception {
        try (var db = dataSource.getConnection()) {
            assertTrue(db.getMetaData().getURL().startsWith("jdbc:h2:mem:withdrawal_review"));
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }
    OAuthUserInfo info(String provider, String identity) {
        return new OAuthUserInfo(provider, identity, "withdraw@example.invalid", "Withdrawal", "https://example.invalid/oauth.png");
    }
    Client member(String provider) throws Exception {
        var member = oauth.processLogin(info(provider, "withdraw-" + UUID.randomUUID()));
        jdbc.update("update members set nickname='Withdrawal',profile_completed=true where id=?", member.getId());
        return login(member.getId(), info(provider, member.getProviderId()));
    }
    Client login(long id, OAuthUserInfo info) throws Exception {
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", info.providerId()), "sub", info, id);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), info.provider()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        String json = mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Client(id, principal, session, json.split("\"token\":\"")[1].split("\"")[0]);
    }
    ResultActions delete(Client client, long expected, String confirmation) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/users/me")
            .session(client.session()).header("X-CSRF-TOKEN", client.csrf()).contentType("application/json")
            .content("{\"expectedUserId\":" + expected + ",\"confirmation\":\"" + confirmation + "\"}"));
    }
    long count(String table, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + (table.equals("members") ? "id" : "member_id") + "=?", Long.class, id);
    }
    void seedPersonalData(Client client) throws Exception {
        String body = "{\"mountainId\":47,\"courseId\":300,\"startedAt\":\"2020-01-01T01:00:00Z\","
            + "\"endedAt\":\"2020-01-01T01:20:00Z\",\"distanceMeters\":500,\"elapsedMs\":1200000,"
            + "\"completed\":true,\"clientRequestId\":\"" + UUID.randomUUID() + "\","
            + "\"gpsSegments\":[[{\"lat\":37.6586301,\"lng\":126.9780032,\"accuracyMeters\":5,\"measuredAt\":\"2020-01-01T01:01:00Z\"},"
            + "{\"lat\":" + (37.6586301 + Math.toDegrees(500.0 / 6_371_000))
            + ",\"lng\":126.9780032,\"accuracyMeters\":5,\"measuredAt\":\"2020-01-01T01:10:00Z\"}]]}";
        mvc.perform(post("/api/users/me/hiking-records").session(client.session()).header("X-CSRF-TOKEN", client.csrf())
            .contentType("application/json").content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.scoring.status").value("CERTIFIED"))
            .andExpect(jsonPath("$.scoring.visitPoints").value(200))
            .andExpect(jsonPath("$.scoring.summitPoints").value(70));
        long activity = jdbc.queryForObject("select id from hiking_activities where member_id=?", Long.class, client.id());
        // 신규 정책은 일일 원장을 만들지 않는다. 과거 원장 삭제·타 회원 보존 검증용으로 명시적으로 준비한다.
        jdbc.update("insert into hiking_daily_certifications(member_id,certification_date,activity_id) values(?,?,?)",
            client.id(), java.sql.Date.valueOf("2020-01-01"), activity);
        mvc.perform(post("/api/journals").session(client.session()).header("X-CSRF-TOKEN", client.csrf())
            .contentType("application/json").content("{\"hikingRecordId\":" + activity + ",\"title\":\"Delete me\",\"content\":\"Private text\",\"isPublic\":true}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/users/me/monthly-goals/2026-11").session(client.session()).header("X-CSRF-TOKEN", client.csrf())
            .contentType("application/json").content("{\"distanceKm\":10,\"hikeCount\":4}")).andExpect(status().isOk());
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        for (String kind : List.of("profile", "background"))
            mvc.perform(multipart("/api/users/me/images/" + kind)
                .file(new MockMultipartFile("file", "image.png", "image/png", bytes.toByteArray()))
                .session(client.session()).header("X-CSRF-TOKEN", client.csrf())
                .with(request -> { request.setMethod("PUT"); return request; })).andExpect(status().isOk());
    }
    @Test void deletesAllOwnedRowsIncludingActualImageBytesAndKeepsOtherMembersAndCatalog() throws Exception {
        var owner = member("google"); var other = member("kakao");
        seedPersonalData(owner); seedPersonalData(other);
        long mountains = jdbc.queryForObject("select count(*) from mountains", Long.class);
        assertEquals(320L, jdbc.queryForObject("select score from members where id=?", Long.class, owner.id()));
        assertTrue(jdbc.queryForObject("select sum(octet_length(content)) from member_personal_images where member_id=?", Long.class, owner.id()) > 0);
        delete(owner, owner.id(), "탈퇴").andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        for (String table : List.of("members", "hiking_activities", "hiking_records", "member_personal_images",
            "hiking_activity_scores", "hiking_daily_certifications", "hiking_public_bonuses", "hiking_score_events", "member_monthly_goals")) {
            assertEquals(0, count(table, owner.id()), table);
            assertTrue(count(table, other.id()) > 0, table + " other member");
        }
        assertEquals(mountains, jdbc.queryForObject("select count(*) from mountains", Long.class));
        assertTrue(owner.session().isInvalid());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/" + owner.id() + "/profile").session(other.session())).andExpect(status().isNotFound());
    }
    @Test void rejectsEveryOtherDeviceSessionAndNeverResurrectsItAfterSameSocialRejoin() throws Exception {
        var owner = member("google"); var identity = owner.principal().getUserInfo();
        var device = login(owner.id(), identity); var dormantDevice = login(owner.id(), identity);
        delete(owner, owner.id(), "탈퇴").andExpect(status().isNoContent());
        mvc.perform(get("/api/mountains").session(device.session())).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("account_unavailable")).andExpect(jsonPath("$.userId").value(owner.id()));
        assertTrue(device.session().isInvalid());
        var rejoined = oauth.processLogin(identity);
        assertNotEquals(owner.id(), rejoined.getId()); assertFalse(rejoined.isProfileCompleted()); assertNull(rejoined.getScore());
        var fresh = login(rejoined.getId(), identity);
        mvc.perform(get("/api/users/me").session(fresh.session())).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(rejoined.getId()));
        mvc.perform(get("/api/users/me").session(dormantDevice.session())).andExpect(status().isUnauthorized());
        assertNotEquals(owner.principal().getName(), fresh.principal().getName());
        for (String table : List.of("hiking_activities", "hiking_records", "member_personal_images", "member_monthly_goals"))
            assertEquals(0, count(table, rejoined.getId()));
    }
    @Test void kakaoRejoinAlsoCreatesNewMemberAndOnlyInternalOAuthMappingIsRemoved() throws Exception {
        var owner = member("kakao"); var info = owner.principal().getUserInfo();
        delete(owner, owner.id(), "탈퇴").andExpect(status().isNoContent());
        assertEquals(0L, jdbc.queryForObject("select count(*) from members where provider=? and provider_id=?", Long.class, info.provider(), info.providerId()));
        assertNotEquals(owner.id(), oauth.processLogin(info).getId()); // 공급자 API 호출 없이 내부 회원 서비스를 직접 사용한다.
    }
    @Test void authenticationCsrfConfirmationAndAccountSwitchPreconditionAreRequired() throws Exception {
        // 세션 없는 DELETE도 CSRF 토큰이 없으므로 인증 진입점보다 앞선 CSRF 거부를 관찰한다.
        var owner = member("google"); var other = member("google");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/users/me"))
            .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/users/me")
            .session(owner.session()).contentType("application/json").content("{\"expectedUserId\":" + owner.id() + ",\"confirmation\":\"탈퇴\"}"))
            .andExpect(status().isForbidden());
        delete(owner, owner.id(), "wrong").andExpect(status().isBadRequest());
        delete(owner, other.id(), "탈퇴").andExpect(status().isConflict());
        assertEquals(1, count("members", owner.id())); assertEquals(1, count("members", other.id()));
    }
    @Test void unboundPreUpgradePrincipalMustReloginAndCannotBindItselfToARejoinedAccount() throws Exception {
        var owner = member("google"); var info = owner.principal().getUserInfo();
        var old = new OAuthPrincipal(List.of(), Map.of("sub", info.providerId()), "sub", info);
        var security = SecurityContextHolder.createEmptyContext(); security.setAuthentication(new OAuth2AuthenticationToken(old, old.getAuthorities(), "google"));
        var session = new MockHttpSession(); session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("session_upgrade_required"));
        assertEquals(1, count("members", owner.id()));
    }
    @Test void failedDeleteRollsBackEveryDependentRowAndKeepsSessionUsable() throws Exception {
        // 회원을 참조하는 임시 외래 키로 마지막 회원 삭제를 실패시켜 앞선 삭제의 롤백을 확인하며, 컨트롤러 정리는 실행하지 않는다.
        var owner = member("google"); seedPersonalData(owner);
        jdbc.execute("create table withdrawal_test_guard(member_id bigint references members(id))");
        try {
            jdbc.update("insert into withdrawal_test_guard values(?)", owner.id());
            assertThrows(DataIntegrityViolationException.class, () -> withdrawals.withdraw(owner.principal(), new WithdrawalRequest(owner.id(), "탈퇴")));
            assertEquals(1, count("members", owner.id())); assertEquals(2, count("member_personal_images", owner.id()));
            assertEquals(1, count("hiking_records", owner.id())); assertEquals(1, count("hiking_activities", owner.id()));
            assertEquals(320L, jdbc.queryForObject("select score from members where id=?", Long.class, owner.id()));
            mvc.perform(get("/api/users/me").session(owner.session())).andExpect(status().isOk());
        } finally { jdbc.execute("drop table withdrawal_test_guard"); }
    }
    @Test void removesAuthorizedClientTokensWithoutTouchingAnotherAccountOrProviderAccount() throws Exception {
        var owner = member("google"); var other = member("google");
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-token",
            java.time.Instant.now(), java.time.Instant.now().plusSeconds(600));
        var registration = registrations.findByRegistrationId("google");
        for (var client : List.of(owner, other)) clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, client.principal().getName(), token),
            new OAuth2AuthenticationToken(client.principal(), client.principal().getAuthorities(), "google"));
        delete(owner, owner.id(), "탈퇴").andExpect(status().isNoContent());
        assertNull(clients.loadAuthorizedClient("google", owner.principal().getName()));
        assertNotNull(clients.loadAuthorizedClient("google", other.principal().getName()));
    }
    @Test void concurrentWithdrawalsCannotPartiallyDeleteOrAffectOtherAccounts() throws Exception {
        var owner = member("google"); seedPersonalData(owner); var otherDevice = login(owner.id(), owner.principal().getUserInfo());
        var other = member("google"); var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { gate.await(); return delete(owner, owner.id(), "탈퇴").andReturn().getResponse().getStatus(); });
            var two = pool.submit(() -> { gate.await(); return delete(otherDevice, owner.id(), "탈퇴").andReturn().getResponse().getStatus(); });
            gate.countDown(); var statuses = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            assertTrue(statuses.contains(204)); assertTrue(statuses.stream().allMatch(s -> s == 204 || s == 401));
            assertEquals(0, count("members", owner.id())); assertEquals(0, count("member_personal_images", owner.id()));
            assertEquals(1, count("members", other.id()));
        } finally { pool.shutdownNow(); }
    }
    @Test void incompleteProfileCanAlsoWithdrawWithoutCompletingSetup() throws Exception {
        var owner = member("google"); jdbc.update("update members set profile_completed=false where id=?", owner.id());
        delete(owner, owner.id(), "탈퇴").andExpect(status().isNoContent()); assertEquals(0, count("members", owner.id()));
    }
}
