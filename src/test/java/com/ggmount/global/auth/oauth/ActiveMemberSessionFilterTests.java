package com.ggmount.global.auth.oauth;

import com.ggmount.member.repository.MemberRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveMemberSessionFilterTests {
    final MemberRepository members = mock(MemberRepository.class);
    final ActiveMemberSessionFilter filter = new ActiveMemberSessionFilter(members);
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    MockHttpServletRequest request(Long id, String path) {
        var info = new OAuthUserInfo("google", "same-social", null, null, null);
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", info.providerId()), "sub", info, id);
        SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var request = new MockHttpServletRequest("GET", path); request.setServletPath(path);
        request.setSession(new MockHttpSession()); return request;
    }
    @Test void liveBoundAccountPassesWithoutChangingExistingSession() throws Exception {
        var request = request(1L, "/api/rankings/summary"); var response = new MockHttpServletResponse();
        when(members.existsByIdAndProviderAndProviderId(1L, "google", "same-social")).thenReturn(true);
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertTrue(called.get()); assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void retiredIncarnationCannotBecomeNewMemberWithSameSocialIdentity() throws Exception {
        var request = request(1L, "/api/mountains"); var session = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        when(members.existsByIdAndProviderAndProviderId(1L, "google", "same-social")).thenReturn(false);
        when(members.existsByIdAndProviderAndProviderId(2L, "google", "same-social")).thenReturn(true);
        filter.doFilter(request, response, (req, res) -> fail("retired session reached API"));
        assertEquals(401, response.getStatus()); assertTrue(session.isInvalid());
        assertTrue(response.getContentAsString().contains("account_unavailable"));
        assertTrue(response.getContentAsString().contains("\"userId\":1"));
        verify(members, never()).findByProviderAndProviderId(anyString(), anyString());
        verify(members, never()).existsByIdAndProviderAndProviderId(2L, "google", "same-social");
    }
    @Test void databaseOutageIsNotMisreportedAsDeletedAccountAndDoesNotDestroySession() throws Exception {
        var request = request(1L, "/api/users/me"); var session = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        when(members.existsByIdAndProviderAndProviderId(anyLong(), anyString(), anyString()))
            .thenThrow(new DataAccessResourceFailureException("private connection details"));
        filter.doFilter(request, response, (req, res) -> fail("unverified request passed"));
        assertEquals(503, response.getStatus()); assertFalse(session.isInvalid());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertFalse(response.getContentAsString().contains("private"));
    }
    @Test void preUpgradeUnboundPrincipalRequiresNewLoginWithoutClaimingAccountDeletion() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(null, "/api/users/me"), response, (req, res) -> fail("unbound session passed"));
        assertEquals(401, response.getStatus()); assertTrue(response.getContentAsString().contains("session_upgrade_required"));
        assertFalse(response.getContentAsString().contains("userId")); verifyNoInteractions(members);
    }
    @Test void existingCsrfAndLogoutEndpointsKeepTheirSecurityChainBehavior() throws Exception {
        for (String path : List.of("/api/csrf", "/api/logout", "/oauth2/authorization/google")) {
            var called = new AtomicBoolean(); var response = new MockHttpServletResponse();
            filter.doFilter(request(1L, path), response, (req, res) -> called.set(true));
            assertTrue(called.get());
        }
        verifyNoInteractions(members);
    }
}
