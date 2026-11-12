package com.ggmount.global.auth.oauth;

import com.ggmount.member.repository.MemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 탈퇴 회원의 기존 세션을 걸러내기 위해 세션의 회원 식별 정보가 데이터베이스에 존재하는지 검증한다.
 * 서버 메모리의 폐기 목록 대신 내부 회원 ID·공급자·공급자 사용자 ID를 함께 조회한다.
 * 재가입해도 이전 세션의 내부 회원 ID로 조회하므로, 공급자 계정이 같다는 이유만으로 유효하게 처리하지 않는다.
 */
public class ActiveMemberSessionFilter extends OncePerRequestFilter {
    private final MemberRepository members;
    public ActiveMemberSessionFilter(MemberRepository members) { this.members = members; }

    @Override
    // /api/ 요청 중 CSRF 토큰 발급과 로그아웃 경로는 이 필터의 검증에서 제외한다.
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/csrf") || path.equals("/api/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // OAuth2 인증의 사용자 정보가 현재 세션 형식이 아니면 세션과 인증 정보를 정리하고 재로그인을 요구한다.
        if (authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
            && !(authentication.getPrincipal() instanceof OAuthPrincipal)) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
            json(response, 401, "session_upgrade_required", "다시 로그인해주세요.");
            return;
        }
        if (authentication != null && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof OAuthPrincipal principal) {
            boolean active;
            try {
                var info = principal.getUserInfo();
                // 내부 회원 ID가 없으면 조회 없이 검증에 실패하며, 있으면 공급자 식별 정보까지 함께 대조한다.
                active = principal.getMemberId() != null && members.existsByIdAndProviderAndProviderId(
                    principal.getMemberId(), info.provider(), info.providerId());
            } catch (DataAccessException unavailable) {
                // 조회 장애는 인증 실패로 단정하지 않고 세션을 유지한 채 503 응답으로 요청 처리를 중단한다.
                json(response, 503, "session_check_unavailable", "서버에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해주세요.");
                return;
            }
            if (!active) {
                // 내부 회원 ID가 없거나 일치하는 회원이 없으면 세션·인증 정보를 정리하고 세션 쿠키를 삭제한다.
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
                // 식별자가 없는 세션은 갱신을 요구하고, 식별자가 있는 세션은 계정을 사용할 수 없음을 401로 알린다.
                if (principal.getMemberId() == null)
                    json(response, 401, "session_upgrade_required", "보안 설정이 변경되었습니다. 다시 로그인해주세요.");
                else {
                    response.setStatus(401);
                    response.setHeader("Cache-Control", "no-store");
                    response.setCharacterEncoding("UTF-8"); response.setContentType("application/json");
                    // 검증에 실패한 인증 세션에 저장되어 있던 내부 회원 ID를 해당 요청의 응답에 포함한다.
                    response.getWriter().write("{\"error\":\"account_unavailable\",\"userId\":"
                        + principal.getMemberId() + ",\"message\":\"탈퇴했거나 유효하지 않은 계정입니다. 다시 로그인해주세요.\"}");
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void json(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
