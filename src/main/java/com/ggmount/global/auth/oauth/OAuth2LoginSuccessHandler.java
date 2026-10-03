package com.ggmount.global.auth.oauth;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Component;

@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private final String frontendUrl;
    public OAuth2LoginSuccessHandler(@org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) throws IOException {
        // 공통 정보는 ((OAuthPrincipal) authentication.getPrincipal()).getUserInfo()로 접근합니다.
        // 기존 세션 인증을 유지하며 토큰을 URL에 전달하지 않습니다.
        clearAuthenticationAttributes(request);
        new HttpSessionRequestCache().removeRequest(request, response);
        response.sendRedirect(frontendUrl + "/?oauth=success");
    }
}
