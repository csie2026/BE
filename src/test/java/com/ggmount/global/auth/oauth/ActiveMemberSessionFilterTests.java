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

// 회원 저장소 Mock과 모의 요청·세션으로 필터 분기를 검사한다. 실제 DB 탈퇴·세션 만료·동시 요청은 검증하지 않는다.
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
    // 회원·공급자 정보가 일치하면 다음 필터 호출과 인증 정보 존재를 확인한다. 기존 세션의 동일성은 직접 단언하지 않는다.
    @Test void liveBoundAccountPassesWithoutChangingExistingSession() throws Exception {
        var request = request(1L, "/api/rankings/summary"); var response = new MockHttpServletResponse();
        when(members.existsByIdAndProviderAndProviderId(1L, "google", "same-social")).thenReturn(true);
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertTrue(called.get()); assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
    // 이전 ID가 사라졌으면 401·세션 무효화·기존 ID 응답을 확인하고, 같은 공급자 계정의 신규 ID로 조회하지 않는지 검사한다.
    // 이 사례는 인증 정보 제거나 세션 쿠키 삭제를 직접 단언하지 않는다.
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
    // DB 조회 예외는 503으로 차단하되 세션·인증을 보존하고 예외의 비공개 상세가 응답에 없는지 확인한다.
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
    // 내부 회원 ID가 없으면 DB 조회 없이 갱신 요구 401을 반환하고 사용자 ID를 노출하지 않는지 확인한다.
    @Test void preUpgradeUnboundPrincipalRequiresNewLoginWithoutClaimingAccountDeletion() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(null, "/api/users/me"), response, (req, res) -> fail("unbound session passed"));
        assertEquals(401, response.getStatus()); assertTrue(response.getContentAsString().contains("session_upgrade_required"));
        assertFalse(response.getContentAsString().contains("userId")); verifyNoInteractions(members);
    }
    // 제외 경로에서는 DB 조회 없이 다음 필터를 호출하는지만 확인하며 실제 CSRF·로그아웃 동작은 실행하지 않는다.
    @Test void existingCsrfAndLogoutEndpointsKeepTheirSecurityChainBehavior() throws Exception {
        for (String path : List.of("/api/csrf", "/api/logout", "/oauth2/authorization/google")) {
            var called = new AtomicBoolean(); var response = new MockHttpServletResponse();
            filter.doFilter(request(1L, path), response, (req, res) -> called.set(true));
            assertTrue(called.get());
        }
        verifyNoInteractions(members);
    }
}
