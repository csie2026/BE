package com.ggmount;

import com.ggmount.global.auth.oauth.*;
import com.ggmount.member.domain.Member;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.OAuthMemberService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 실제 OAuth 왕복·공유 쿠키 브라우저가 아닌, 격리 H2와 실제 보안 필터·서비스의 요청 경계를 검증한다.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:profile_account_safety;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.url=jdbc:h2:mem:profile_account_safety;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password=", "app.scoring.rebuild-on-start=false"
})
@Transactional
class ProfileAccountSafetyIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired OAuthMemberService oauth;
    @Autowired MemberRepository members;
    @Autowired EntityManager entities;
    MockMvc mvc;
    record Account(long id, MockHttpSession session) {}

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }
    Account account(String key, boolean complete) {
        var info = new OAuthUserInfo("google", key, "test@example.invalid", key, null);
        var member = oauth.processLogin(info);
        if (complete) member.completeProfile(key, 2000);
        members.flush();
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", key), "sub", info, member.getId());
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return new Account(member.getId(), session);
    }
    String csrf(MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return (String) org.springframework.boot.json.JsonParserFactory.getJsonParser().parseMap(response).get("token");
    }
    String body(long id) {
        return "{\"expectedUserId\":" + id + ",\"nickname\":\"Updated\",\"birthYear\":2003}";
    }
    Member fresh(long id) {
        members.flush(); entities.clear();
        return members.findById(id).orElseThrow();
    }
    @Test void correctAccountCanCompleteFirstProfile() throws Exception {
        var a = account("new-profile", false);
        mvc.perform(patch("/api/users/me/profile").session(a.session()).header("X-CSRF-TOKEN", csrf(a.session()))
            .contentType(MediaType.APPLICATION_JSON).content(body(a.id())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(a.id()))
            .andExpect(jsonPath("$.profileCompleted").value(true)).andExpect(jsonPath("$.nickname").value("Updated"));
        assertTrue(fresh(a.id()).isProfileCompleted());
    }
    @Test void correctAccountCanEditNicknameAndBirthYear() throws Exception {
        var a = account("existing-profile", true);
        mvc.perform(patch("/api/users/me/profile").session(a.session()).header("X-CSRF-TOKEN", csrf(a.session()))
            .contentType(MediaType.APPLICATION_JSON).content(body(a.id()))).andExpect(status().isOk());
        var changed = fresh(a.id());
        assertEquals("Updated", changed.getNickname()); assertEquals(2003, changed.getBirthYear());
    }
    @Test void bSessionWithValidBCsrfCannotApplyAInputToEitherAccount() throws Exception {
        var a = account("account-a", true); var b = account("account-b", true);
        mvc.perform(patch("/api/users/me/profile").session(b.session()).header("X-CSRF-TOKEN", csrf(b.session()))
            .contentType(MediaType.APPLICATION_JSON).content(body(a.id())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("profile_account_mismatch"))
            .andExpect(jsonPath("$.message").isString()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.userId").doesNotExist());
        assertEquals("account-a", fresh(a.id()).getNickname()); assertEquals(2000, fresh(a.id()).getBirthYear());
        assertEquals("account-b", fresh(b.id()).getNickname()); assertEquals(2000, fresh(b.id()).getBirthYear());
    }
    @Test void staleFirstSetupCannotCompleteAnotherNewAccount() throws Exception {
        var a = account("new-a", false); var b = account("new-b", false);
        mvc.perform(patch("/api/users/me/profile").session(b.session()).header("X-CSRF-TOKEN", csrf(b.session()))
            .contentType(MediaType.APPLICATION_JSON).content(body(a.id()))).andExpect(status().isConflict());
        assertFalse(fresh(a.id()).isProfileCompleted()); assertFalse(fresh(b.id()).isProfileCompleted());
        assertEquals("new-b", fresh(b.id()).getNickname());
    }
    @Test void missingOrInvalidExpectedIdsAreRejectedWithoutWriting() throws Exception {
        var a = account("invalid-id", true); String token = csrf(a.session());
        for (String fragment : List.of("", "\"expectedUserId\":null,", "\"expectedUserId\":0,",
            "\"expectedUserId\":-1,", "\"expectedUserId\":1.5,", "\"expectedUserId\":\"bad\",",
            "\"expectedUserId\":{},", "\"expectedUserId\":9223372036854775808,")) {
            mvc.perform(patch("/api/users/me/profile").session(a.session()).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content("{" + fragment + "\"nickname\":\"Updated\",\"birthYear\":2003}"))
                .andExpect(status().isBadRequest());
        }
        assertEquals("invalid-id", fresh(a.id()).getNickname()); assertEquals(2000, fresh(a.id()).getBirthYear());
    }
    @Test void unauthenticatedSessionWithValidCsrfCannotModifyProfile() throws Exception {
        var a = account("expired-target", true); var anonymous = new MockHttpSession();
        mvc.perform(patch("/api/users/me/profile").session(anonymous).header("X-CSRF-TOKEN", csrf(anonymous))
            .contentType(MediaType.APPLICATION_JSON).content(body(a.id()))).andExpect(status().isUnauthorized());
        assertEquals("expired-target", fresh(a.id()).getNickname());
    }
    @Test void missingAndInvalidCsrfStillRejectEvenMatchingAccount() throws Exception {
        var a = account("csrf-target", true); csrf(a.session());
        mvc.perform(patch("/api/users/me/profile").session(a.session()).contentType(MediaType.APPLICATION_JSON)
            .content(body(a.id()))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/users/me/profile").session(a.session()).header("X-CSRF-TOKEN", "wrong")
            .contentType(MediaType.APPLICATION_JSON).content(body(a.id()))).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("invalid_csrf"));
        assertEquals("csrf-target", fresh(a.id()).getNickname());
    }
}
