package com.ggmount;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

// 설정만 로딩하며 웹 서버·DB 연결·인증 제공자 요청은 실행하지 않는다.
class MobileProfileConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.config.location=file:./src/main/resources/");

    @Test void localRetainsRequestBasedCallbacks() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertNull(context.getStartupFailure());
            var env = context.getEnvironment();
            for (String provider : new String[] {"google", "kakao"}) {
                assertEquals("{baseUrl}/login/oauth2/code/{registrationId}", env.getProperty(
                    "spring.security.oauth2.client.registration." + provider + ".redirect-uri"));
            }
            assertEquals("optional:file:./.env[.properties]", env.getProperty("spring.config.import"));
        });
    }

    @Test void mobileOverridesOnlyPublicAddressesAndHttpsCookiesWhileKeepingLocalImport() {
        runner.withPropertyValues("spring.profiles.active=local,mobile").run(context -> {
            assertNull(context.getStartupFailure());
            var env = context.getEnvironment();
            // 임시 터널 호스트가 바뀌어도 공개 HTTPS origin과 콜백의 일관성을 검증한다.
            String origin = env.getRequiredProperty("app.frontend-url");
            URI uri = URI.create(origin);
            assertEquals("https", uri.getScheme());
            assertNotNull(uri.getHost());
            assertFalse(uri.getHost().isBlank());
            assertEquals("", uri.getRawPath());
            assertNull(uri.getRawUserInfo());
            assertNull(uri.getRawQuery());
            assertNull(uri.getRawFragment());
            for (String provider : new String[] {"google", "kakao"}) {
                assertEquals(origin + "/login/oauth2/code/" + provider, env.getProperty(
                    "spring.security.oauth2.client.registration." + provider + ".redirect-uri"));
            }
            assertEquals("true", env.getProperty("server.servlet.session.cookie.secure"));
            assertEquals("true", env.getProperty("server.servlet.session.cookie.http-only"));
            assertEquals("lax", env.getProperty("server.servlet.session.cookie.same-site"));
            assertEquals("optional:file:./.env[.properties]", env.getProperty("spring.config.import"));
            assertEquals("none", env.getProperty("spring.jpa.hibernate.ddl-auto"));
        });
    }

    @Test void mobileCallbacksFollowAnOverriddenPublicOrigin() {
        String origin = "https://mobile-profile.example.invalid";
        runner.withPropertyValues("spring.profiles.active=local,mobile", "app.frontend-url=" + origin)
            .run(context -> {
                assertNull(context.getStartupFailure());
                for (String provider : new String[] {"google", "kakao"}) {
                    assertEquals(origin + "/login/oauth2/code/" + provider, context.getEnvironment().getProperty(
                        "spring.security.oauth2.client.registration." + provider + ".redirect-uri"));
                }
            });
    }
}
