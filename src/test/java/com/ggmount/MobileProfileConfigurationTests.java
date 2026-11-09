package com.ggmount;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

// Configuration-only checks: no web server, database connection, or provider requests.
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
            String origin = "https://writes-strain-strap-tribunal.trycloudflare.com";
            assertEquals(origin, env.getProperty("app.frontend-url"));
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
}
