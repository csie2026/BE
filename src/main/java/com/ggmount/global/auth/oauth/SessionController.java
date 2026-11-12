package com.ggmount.global.auth.oauth;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class SessionController {
    /**
     * CSRF 토큰과 전송할 헤더 이름을 반환한다. 인증 없이 접근 가능하므로 발급 성공이 로그인 완료를 뜻하지 않는다.
     */
    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
            "headerName",
            token.getHeaderName(),
            "token",
            token.getToken()
        );
    }
}
