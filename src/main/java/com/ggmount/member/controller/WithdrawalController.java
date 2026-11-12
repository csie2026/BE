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

    // 요청의 예상 회원 ID와 확인 문구를 검증한 뒤 현재 인증 회원의 탈퇴를 처리한다.
    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal OAuthPrincipal principal,
        @Valid @RequestBody WithdrawalRequest body, Authentication authentication,
        HttpServletRequest request, HttpServletResponse response) {
        // 서비스 프록시의 트랜잭션 커밋이 성공한 뒤 인증 클라이언트·세션 정리와 성공 응답을 진행한다.
        // 삭제나 커밋이 실패하면 예외를 전파하며 아래 정리 과정은 실행하지 않는다.
        withdrawals.withdraw(principal, body);
        // 현재 공급자의 인증 클라이언트를 내부 회원 ID 기반 이름과 과거 공급자 식별자 조합 이름으로 각각 제거한다.
        // 로컬 인증 클라이언트 저장소를 정리하며 공급자 계정 자체를 삭제하는 호출은 아니다.
        clients.removeAuthorizedClient(principal.getUserInfo().provider(), principal.getName());
        clients.removeAuthorizedClient(principal.getUserInfo().provider(),
            principal.getUserInfo().provider() + ":" + principal.getUserInfo().providerId());
        // 현재 세션을 무효화하고 인증 정보를 정리한 뒤 세션 쿠키를 삭제한다.
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
