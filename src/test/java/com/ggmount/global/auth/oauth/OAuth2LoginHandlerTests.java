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

class OAuth2LoginHandlerTests {

    @Test
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
