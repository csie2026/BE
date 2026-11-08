package com.ggmount.member.controller;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.WithdrawalRequest;
import com.ggmount.member.service.WithdrawalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class WithdrawalController {
    private final WithdrawalService withdrawals;
    private final OAuth2AuthorizedClientService clients;

    public WithdrawalController(WithdrawalService withdrawals, OAuth2AuthorizedClientService clients) {
        this.withdrawals = withdrawals;
        this.clients = clients;
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal OAuthPrincipal principal,
        @Valid @RequestBody WithdrawalRequest body, Authentication authentication,
        HttpServletRequest request, HttpServletResponse response) {
        // Proxied transaction commits before any session/client cleanup or success response.
        withdrawals.withdraw(principal, body);
        clients.removeAuthorizedClient(principal.getUserInfo().provider(), principal.getName());
        clients.removeAuthorizedClient(principal.getUserInfo().provider(),
            principal.getUserInfo().provider() + ":" + principal.getUserInfo().providerId());
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
