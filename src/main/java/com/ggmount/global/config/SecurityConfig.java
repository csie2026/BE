package com.ggmount.global.config;

import com.ggmount.global.auth.oauth.CustomOAuth2UserService;
import com.ggmount.global.auth.oauth.OAuth2LoginFailureHandler;
import com.ggmount.global.auth.oauth.OAuth2LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            CustomOAuth2UserService userService,
            OAuth2LoginSuccessHandler successHandler,
            OAuth2LoginFailureHandler failureHandler) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/oauth2/authorization/**", "/login/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo.userService(userService))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        // 기본 세션 정책과 CSRF 보호를 유지합니다.
        return http.build();
    }
}
