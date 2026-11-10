package com.ggmount.global.auth.oauth;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

// 로그인 실패 응답은 최소 정보만 전달해 외부 공급자 응답이나 내부 예외 정보가 노출되지 않게 한다.
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {
    private final String frontendUrl;
    public OAuth2LoginFailureHandler(@org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException exception
    ) throws IOException {
        // 공급자 응답 및 예외 메시지는 클라이언트에 노출하지 않습니다.
        var session = request.getSession(false);
        if (session != null) session.removeAttribute(org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION);
        new org.springframework.security.web.savedrequest.HttpSessionRequestCache().removeRequest(request, response);
        response.setHeader("Cache-Control", "no-store");
        // Use only trusted server configuration, never a request-supplied return URL or provider message.
        response.sendRedirect(frontendUrl + "/?oauth=failed");
    }
}
