package com.ggmount;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

// prod의 프록시·쿠키 설정을 임의 포트 로컬 서버에서 검사한다. HTTP로 응답 헤더를 읽으며 브라우저의 Secure 쿠키 전송은 검증하지 않는다.
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "FRONTEND_URL=https://localhost")
class ProductionSessionTests {
    @LocalServerPort int port;

    @Test void proxyHttpsSchemeIsUsedForBothProviderCallbacks() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String provider : new String[] {"google", "kakao"}) {
                var login = URI.create("http://127.0.0.1:" + port + "/oauth2/authorization/" + provider);
                var request = HttpRequest.newBuilder(login).header("X-Forwarded-Proto", "https").build();
                var response = client.send(request, HttpResponse.BodyHandlers.discarding());
                assertEquals(302, response.statusCode());
                var redirect = URI.create(response.headers().firstValue("Location").orElseThrow());
                String callback = Arrays.stream(redirect.getRawQuery().split("&"))
                    .filter(pair -> pair.startsWith("redirect_uri="))
                    .map(pair -> java.net.URLDecoder.decode(pair.substring("redirect_uri=".length()),
                        java.nio.charset.StandardCharsets.UTF_8))
                    .findFirst().orElseThrow();
                assertEquals("https://127.0.0.1/login/oauth2/code/" + provider, callback);
            }
        }
    }

    @Test void csrfIssuesProtectedSessionCookieAndLogoutRequiresToken() throws Exception {
        var base = URI.create("http://127.0.0.1:" + port);
        try (var client = HttpClient.newHttpClient()) {
            var csrf = client.send(HttpRequest.newBuilder(base.resolve("/api/csrf")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrf.statusCode());
            String cookie = csrf.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
            var attributes = Arrays.asList(cookie.toLowerCase(java.util.Locale.ROOT).split("; *"));
            assertTrue(attributes.contains("secure"));
            assertTrue(attributes.contains("httponly"));
            assertTrue(attributes.contains("samesite=lax"));
            var logout = HttpRequest.newBuilder(base.resolve("/api/logout"))
                .header("Cookie", cookie.split(";", 2)[0]).POST(HttpRequest.BodyPublishers.noBody());
            assertEquals(403, client.send(logout.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            var token = new tools.jackson.databind.ObjectMapper().readTree(csrf.body());
            logout.header(token.get("headerName").asString(), token.get("token").asString());
            assertEquals(204, client.send(logout.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test void providerCancellationReturnsToFrontendThroughTheRealSecurityFilterWithoutProviderCalls() throws Exception {
        var base = URI.create("http://127.0.0.1:" + port);
        // 리다이렉트를 따라가지 않아 요청은 로컬 서버에만 전송된다. 공급자 취소 콜백은 직접 구성한다.
        try (var client = HttpClient.newHttpClient()) {
            for (String provider : new String[]{"google","kakao"}) {
                var start = client.send(HttpRequest.newBuilder(base.resolve("/oauth2/authorization/" + provider)).build(),
                    HttpResponse.BodyHandlers.discarding());
                assertEquals(302,start.statusCode());
                var authorize = URI.create(start.headers().firstValue("Location").orElseThrow());
                String state = Arrays.stream(authorize.getRawQuery().split("&"))
                    .filter(pair -> pair.startsWith("state=")).findFirst().orElseThrow();
                String cookie = start.headers().allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow().split(";",2)[0];
                var callback = base.resolve("/login/oauth2/code/" + provider
                    + "?error=access_denied&error_description=private-provider-error&" + state
                    + "&redirect=https://attacker.invalid");
                var failed = client.send(HttpRequest.newBuilder(callback).header("Cookie",cookie).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(302,failed.statusCode());
                assertEquals("https://localhost/?oauth=failed",failed.headers().firstValue("Location").orElseThrow());
                assertFalse(failed.body().contains("private-provider-error"));
            }
        }
    }
}
