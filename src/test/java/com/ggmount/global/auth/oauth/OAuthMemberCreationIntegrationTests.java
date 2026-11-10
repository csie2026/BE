package com.ggmount.global.auth.oauth;

import com.ggmount.member.domain.Member;
import com.ggmount.member.dto.MemberUpdateRequest;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.MemberService;
import com.ggmount.member.service.OAuthMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Exercises the real user-info loader, mapper and transactional member services.
 * Only the provider HTTP response is replaced; no outer test transaction hides commits.
 */
@ActiveProfiles("oauth-member-connection")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:oauth_member_connection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:oauth_member_connection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password=",
    "app.scoring.rebuild-on-start=false"
})
class OAuthMemberCreationIntegrationTests {
    private static final String USER_INFO_URI = "https://oauth-provider.invalid/userinfo";
    @Autowired OAuthUserInfoMapper mapper;
    @Autowired OAuthMemberService oauthMembers;
    @Autowired MemberService members;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MemberRepository repository;
    @Autowired PlatformTransactionManager transactions;
    private CustomOAuth2UserService loader;
    private MockRestServiceServer server;
    private String identity;
    private TransactionTemplate read;

    @BeforeEach void setup() {
        identity = Long.toString(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        var rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        // A dedicated instance leaves the application's shared OAuth service untouched.
        loader = new CustomOAuth2UserService(mapper, oauthMembers);
        loader.setRestOperations(rest);
        read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
    }

    @AfterEach void cleanup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (String provider : new String[]{"google", "kakao"})
                repository.findByProviderAndProviderId(provider, identity).ifPresent(repository::delete);
        });
    }

    private OAuthPrincipal login(String provider, boolean optionalProfile) {
        String payload = provider.equals("google")
            ? "{\"sub\":\"" + identity + "\"" + (optionalProfile ? ",\"name\":\"Provider nickname\"" : "") + "}"
            : "{\"id\":" + identity + (optionalProfile
                ? ",\"kakao_account\":{\"profile\":{\"nickname\":\"Provider nickname\"}}" : "") + "}";
        server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        var registration = ClientRegistration.withRegistrationId(provider)
            .clientId("test-client").clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://frontend.invalid/login/oauth2/code/" + provider)
            .authorizationUri("https://oauth-provider.invalid/authorize")
            .tokenUri("https://oauth-provider.invalid/token")
            .userInfoUri(USER_INFO_URI).userNameAttributeName(provider.equals("google") ? "sub" : "id")
            .build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
            "synthetic-test-token", Instant.now(), Instant.now().plusSeconds(60));
        var principal = assertInstanceOf(OAuthPrincipal.class, loader.loadUser(new OAuth2UserRequest(registration, token)));
        server.verify();
        server.reset();
        return principal;
    }

    private Member committedMember(String provider) {
        return read.execute(status -> repository.findByProviderAndProviderId(provider, identity).orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings = {"google", "kakao"})
    void firstLoginCommitsOneIncompleteMemberAndReturningLoginKeepsId(String provider) {
        var principal = login(provider, true);
        var first = committedMember(provider);
        assertNotNull(first.getId());
        assertFalse(first.isProfileCompleted());
        assertNull(first.getBirthYear());
        assertEquals(identity, principal.getUserInfo().providerId());
        assertEquals(provider, principal.getUserInfo().provider());
        assertEquals(first.getId(), members.me(principal).userId());
        login(provider, true);
        assertEquals(first.getId(), committedMember(provider).getId());
        long count = read.execute(status -> repository.findAll().stream()
            .filter(member -> provider.equals(member.getProvider()) && identity.equals(member.getProviderId())).count());
        assertEquals(1L, count);
    }

    @ParameterizedTest @ValueSource(strings = {"google", "kakao"})
    void completedProfileSurvivesReturningLogin(String provider) {
        var principal = login(provider, true);
        var id = committedMember(provider).getId();
        members.update(principal, new MemberUpdateRequest("Chosen nickname", 2003));
        login(provider, true);
        var returning = committedMember(provider);
        assertEquals(id, returning.getId());
        assertEquals("Chosen nickname", returning.getNickname());
        assertEquals(2003, returning.getBirthYear());
        assertTrue(returning.isProfileCompleted());
    }

    @ParameterizedTest @ValueSource(strings = {"google", "kakao"})
    void identityOnlyResponseCommitsMemberWithoutOptionalPersonalData(String provider) {
        login(provider, false);
        var member = committedMember(provider);
        assertNotNull(member.getId());
        assertFalse(member.isProfileCompleted());
        assertNull(member.getNickname());
        assertNull(member.getEmail());
        assertNull(member.getProfileImageUrl());
        assertNull(member.getBirthYear());
    }

    @ParameterizedTest @ValueSource(strings = {"google", "kakao"})
    void concurrentFirstLoginsRecoverOnlyAfterRollbackAndReturnTheSameCommittedMember(String provider) throws Exception {
        var gate = new java.util.concurrent.CyclicBarrier(2);
        var missing = new java.util.concurrent.atomic.AtomicInteger();
        // Force both real transactions to observe the absent row. There is no row to lock yet.
        org.mockito.Mockito.doAnswer(invocation -> {
            var found = repository.findByProviderAndProviderId(provider,identity);
            if (found.isEmpty()) {
                missing.incrementAndGet();
                gate.await(10,java.util.concurrent.TimeUnit.SECONDS);
            }
            return found;
        }).when(repository).lockForLogin(provider,identity);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var info = new OAuthUserInfo(provider,identity,null,"Provider nickname",null);
            var one = pool.submit(() -> oauthMembers.processLogin(info).getId());
            var two = pool.submit(() -> oauthMembers.processLogin(info).getId());
            assertEquals(one.get(20,java.util.concurrent.TimeUnit.SECONDS),two.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2,missing.get());
            assertEquals(one.get(),committedMember(provider).getId());
            long count = read.execute(status -> repository.findAll().stream()
                .filter(m -> provider.equals(m.getProvider()) && identity.equals(m.getProviderId())).count());
            assertEquals(1L,count);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @org.junit.jupiter.api.Test
    void unrelatedDatabaseFailureIsNotRetriedOrTurnedIntoSuccessfulLogin() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("not null",
            new java.sql.SQLException("private database detail","23502"));
        org.mockito.Mockito.doThrow(failure).when(repository).saveAndFlush(org.mockito.ArgumentMatchers.any(Member.class));
        assertSame(failure,assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> oauthMembers.processLogin(new OAuthUserInfo("google",identity,null,null,null))));
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.times(1))
            .saveAndFlush(org.mockito.ArgumentMatchers.any(Member.class));
        boolean absent = read.execute(status -> repository.findByProviderAndProviderId("google",identity).isEmpty());
        assertTrue(absent);
    }
}
