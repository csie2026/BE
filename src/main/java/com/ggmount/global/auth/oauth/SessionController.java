package com.ggmount.global.auth.oauth;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
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
