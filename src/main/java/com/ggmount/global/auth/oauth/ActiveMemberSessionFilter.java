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

/** No JVM-local revoked-ID list: deletion revokes old member identities across devices/nodes. */
public class ActiveMemberSessionFilter extends OncePerRequestFilter {
    private final MemberRepository members;
    public ActiveMemberSessionFilter(MemberRepository members) { this.members = members; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/csrf") || path.equals("/api/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
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
                active = principal.getMemberId() != null && members.existsByIdAndProviderAndProviderId(
                    principal.getMemberId(), info.provider(), info.providerId());
            } catch (DataAccessException unavailable) {
                json(response, 503, "session_check_unavailable", "서버에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해주세요.");
                return;
            }
            if (!active) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
                if (principal.getMemberId() == null)
                    json(response, 401, "session_upgrade_required", "보안 설정이 변경되었습니다. 다시 로그인해주세요.");
                else {
                    response.setStatus(401);
                    response.setHeader("Cache-Control", "no-store");
                    response.setCharacterEncoding("UTF-8"); response.setContentType("application/json");
                    // Only this authenticated former session receives its own retired internal ID.
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
