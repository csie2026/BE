package com.ggmount.global.auth.oauth;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
// 변경 요청에 필요한 현재 세션의 CSRF 토큰과 실제 헤더 이름을 FE에 전달한다.
@RestController
public class SessionController {
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
