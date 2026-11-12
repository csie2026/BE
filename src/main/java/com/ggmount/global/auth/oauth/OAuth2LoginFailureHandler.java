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

    /**
     * 기존 세션의 인증 오류와 저장된 요청을 정리하고, 상세 실패 사유 없이 FE에 실패 여부만 전달한다.
     * 세션이 없으면 새로 만들지 않으며, 실패 응답에는 캐시 저장 금지 헤더를 설정한다.
     */
    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException exception
    ) throws IOException {
        var session = request.getSession(false);
        if (session != null) session.removeAttribute(org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION);
        new org.springframework.security.web.savedrequest.HttpSessionRequestCache().removeRequest(request, response);
        response.setHeader("Cache-Control", "no-store");
        // 복귀 주소는 서버 설정만 사용하며 요청의 복귀 URL이나 공급자 메시지를 리다이렉트에 반영하지 않는다.
        response.sendRedirect(frontendUrl + "/?oauth=failed");
    }
}
