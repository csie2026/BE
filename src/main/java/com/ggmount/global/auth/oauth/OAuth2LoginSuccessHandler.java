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

    /**
     * 이전 오류·저장 요청을 정리하고 원래 요청 경로 대신 설정된 FE로 복귀한다.
     */
    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) throws IOException {
        clearAuthenticationAttributes(request);
        new HttpSessionRequestCache().removeRequest(request, response);
        response.sendRedirect(frontendUrl + "/?oauth=success");
    }
}
