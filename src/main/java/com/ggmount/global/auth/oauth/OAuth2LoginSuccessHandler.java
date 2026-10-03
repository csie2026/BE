package com.ggmount.global.auth.oauth;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Component;

// 로그인 성공 시 FE로 복귀시켜 회원 상태를 다시 조회하게 한다. 인증 정보는 URL이 아닌 서버 세션에 둔다.
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
