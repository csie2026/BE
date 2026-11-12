package com.ggmount.global.auth.oauth;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import static org.junit.jupiter.api.Assertions.*;

// 모의 요청·응답으로 핸들러를 직접 호출한다. Google·Kakao 통신, DB 저장, 쿠키·브라우저·모바일 동작은 검증하지 않는다.
class OAuth2LoginHandlerTests {

    @Test
    // 이름과 달리 JSON이 아니라 설정한 프런트엔드로의 302·oauth=success 및 저장 요청·이전 오류 속성 제거를 확인한다.
    // 내부 회원 ID 없는 이름 호환성을 검사하며 인증의 세션 저장은 직접 단언하지 않는다.
    void successReturnsJsonAndClearsSavedRequestAndPreviousError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.getSession()
            .setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, "previous failure");
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.saveRequest(request, response);
        OAuthPrincipal principal = new OAuthPrincipal(
            List.of(),
            Map.of("sub", "42"),
            "sub",
            new OAuthUserInfo("google", "42", null, null, null)
        );

        new OAuth2LoginSuccessHandler("http://localhost:5173")
            .onAuthenticationSuccess(
                request,
                response,
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google")
            );

        assertEquals(302, response.getStatus());
        assertEquals("http://localhost:5173/?oauth=success", response.getRedirectedUrl());
        assertNull(request.getSession().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION));
        assertNull(cache.getRequest(request, response));
        assertEquals("google:42", principal.getName());
    }

    @Test
    // 요청의 외부 redirect 값을 따르지 않고 설정한 주소의 oauth=failed로 이동하며 no-store·오류 상세 비노출·속성 제거를 확인한다.
    // 여러 허용 주소나 설정 URL 자체의 유효성 검증을 검사하는 테스트는 아니다.
    void failureReturnsToConfiguredFrontendWithoutExposingProviderDetailsOrFollowingReturnUrl() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("redirect", "https://attacker.invalid");
        request.getSession().setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, "private error");
        var cache = new HttpSessionRequestCache();
        cache.saveRequest(request, response);
        new OAuth2LoginFailureHandler("https://frontend.invalid")
            .onAuthenticationFailure(
                request,
                response,
                new BadCredentialsException("private provider response")
            );

        assertEquals(302, response.getStatus());
        assertEquals("https://frontend.invalid/?oauth=failed", response.getRedirectedUrl());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(request.getSession().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION));
        assertNull(cache.getRequest(request,response));
        assertFalse(response.getContentAsString().contains("private"));
        assertFalse(response.getRedirectedUrl().contains("private"));
    }
}
