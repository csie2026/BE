package com.ggmount.global.config;

import com.ggmount.global.auth.oauth.CustomOAuth2UserService;
import com.ggmount.global.auth.oauth.OAuth2LoginFailureHandler;
import com.ggmount.global.auth.oauth.OAuth2LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

/**
 * OAuth2 로그인과 세션 인증을 구성하고, 요청별 접근 권한과 인증·인가 실패 응답을 설정한다.
 * OAuth 진입·로그인 경로, 오류 경로와 CSRF 토큰 발급은 인증 없이 허용하고 나머지는 인증을 요구한다.
 */
@Configuration
public class SecurityConfig {

    /**
     * 기본 세션 정책과 CSRF 보호를 유지하면서 로그인·로그아웃 및 회원 세션 검사 필터를 연결한다.
     * 인증 없이 허용된 경로도 CSRF 검사 제외 경로로 별도 지정하지 않는다.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        CustomOAuth2UserService userService,
        OAuth2LoginSuccessHandler successHandler,
        OAuth2LoginFailureHandler failureHandler,
        com.ggmount.member.repository.MemberRepository members
    ) throws Exception {
        http
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(
                "/oauth2/authorization/**",
                "/login/**",
                "/error",
                "/api/csrf"
            ).permitAll()
            .anyRequest().authenticated())
            // API 인증·권한 실패를 로그인 HTML 대신 401·403 JSON으로 반환해 FE가 세션 만료와 CSRF 실패를 구분하게 한다.
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    response.getWriter()
                    .write("{\"error\":\"unauthorized\",\"message\":\"로그인이 필요합니다. 다시 로그인해주세요.\"}");
                    }
                )
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    String body = exception instanceof CsrfException
                    ? "{\"error\":\"invalid_csrf\",\"message\":\"인증 토큰이 없거나 만료되었습니다. /api/csrf 조회 후 반환된 헤더와 토큰으로 다시 요청해주세요.\"}"
                    : "{\"error\":\"forbidden\",\"message\":\"이 요청에 대한 권한이 없습니다.\"}";
                    response.getWriter().write(body);
                    }
                ))
        // 로그아웃은 서버 세션과 쿠키를 함께 폐기하며 기본 CSRF 보호를 유지한 변경 요청으로 처리한다.
        .logout(logout -> logout.logoutUrl("/api/logout")
            .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
            .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            // 공급자 사용자 정보 처리를 회원 연계 서비스로 연결하고 로그인 결과는 전용 처리기에 위임한다.
            .oauth2Login(oauth -> oauth
                .userInfoEndpoint(userInfo -> userInfo.userService(userService))
                .successHandler(successHandler)
                .failureHandler(failureHandler));
        // 기존 CSRF·로그아웃·OAuth 설정을 유지하며, 인가 전에 세션의 내부 회원 ID와 공급자 식별 정보가 유효한지 검사한다.
        http.addFilterBefore(new com.ggmount.global.auth.oauth.ActiveMemberSessionFilter(members),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
