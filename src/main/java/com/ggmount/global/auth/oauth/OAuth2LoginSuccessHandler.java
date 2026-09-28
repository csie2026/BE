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

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        // 공통 정보는 ((OAuthPrincipal) authentication.getPrincipal()).getUserInfo()로 접근합니다.
        // TODO: 추후 JWT Access Token / Refresh Token 발급
        // TODO: 프론트엔드 연동 시 토큰 전달 방식 및 로그인 완료 응답 확정
        clearAuthenticationAttributes(request);
        new HttpSessionRequestCache().removeRequest(request, response);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":\"success\"}");
    }
}
