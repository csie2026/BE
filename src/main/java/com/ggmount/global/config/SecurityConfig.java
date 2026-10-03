package com.ggmount.global.config;

import com.ggmount.global.auth.oauth.CustomOAuth2UserService;
import com.ggmount.global.auth.oauth.OAuth2LoginFailureHandler;
import com.ggmount.global.auth.oauth.OAuth2LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

// OAuth 진입·콜백과 CSRF 토큰 발급만 인증 없이 허용하고, 나머지 요청은 세션 인증을 요구한다.
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        CustomOAuth2UserService userService,
        OAuth2LoginSuccessHandler successHandler,
        OAuth2LoginFailureHandler failureHandler
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
            .oauth2Login(oauth -> oauth
                .userInfoEndpoint(userInfo -> userInfo.userService(userService))
                .successHandler(successHandler)
                .failureHandler(failureHandler));
        // 기본 세션 정책과 CSRF 보호를 유지합니다.
        return http.build();
    }
}
