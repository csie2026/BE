package com.ggmount;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.OAuthMemberService;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class MemberImageIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired OAuthMemberService oauth;
    @Autowired MemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }

    MockHttpSession login(String provider, String identity) {
        var info = new OAuthUserInfo(provider, identity, "same@example.com", "Social name", "https://example.com/social.png");
        oauth.processLogin(info);
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", identity), "sub", info);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), provider));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }

    String csrf(MockHttpSession session) throws Exception {
        var json = mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return field(json, "token");
    }

    String field(String json, String key) {
        return json.split("\"" + key + "\":\"")[1].split("\"")[0];
    }

    byte[] png(int rgb) throws Exception {
        var image = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, rgb);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    String upload(MockHttpSession session, String kind, byte[] bytes) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/images/" + kind)
                .file(new MockMultipartFile("file", "photo.png", "image/png", bytes))
                .session(session).header("X-CSRF-TOKEN", csrf(session)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    void complete(MockHttpSession session) throws Exception {
        mvc.perform(patch("/api/users/me/profile").session(session).header("X-CSRF-TOKEN", csrf(session))
                .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"Chosen\",\"birthYear\":2003}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profileCompleted").value(true));
    }

    @Test
    void googleAndKakaoNewProfileLogoutAndReturningLoginKeepMemberAndImages() throws Exception {
        for (String provider : List.of("google", "kakao")) {
            var session = login(provider, "lifecycle");
            Long memberId = members.findByProviderAndProviderId(provider, "lifecycle").orElseThrow().getId();
            mvc.perform(get("/api/users/me").session(session))
                    .andExpect(jsonPath("$.profileCompleted").value(false))
                    .andExpect(jsonPath("$.birthYear").isEmpty());
            complete(session);
            byte[] profile = png(0xFF0000), background = png(0x0000FF);
            String profileUrl = field(upload(session, "profile", profile), "profileImageUrl");
            String backgroundUrl = field(upload(session, "background", background), "backgroundImageUrl");
            members.flush(); entityManager.clear();
            mvc.perform(post("/api/logout").session(session).header("X-CSRF-TOKEN", csrf(session)))
                    .andExpect(status().isNoContent());
            assertTrue(session.isInvalid());
            mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
            var refreshed = oauth.processLogin(new OAuthUserInfo(provider, "lifecycle", "changed@example.com", "Changed", null));
            assertEquals(memberId, refreshed.getId());
            var returning = login(provider, "lifecycle");
            mvc.perform(get("/api/users/me").session(returning))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.userId").value(memberId))
                    .andExpect(jsonPath("$.nickname").value("Chosen"))
                    .andExpect(jsonPath("$.birthYear").value(2003))
                    .andExpect(jsonPath("$.profileCompleted").value(true))
                    .andExpect(jsonPath("$.profileImageUrl").value(profileUrl))
                    .andExpect(jsonPath("$.backgroundImageUrl").value(backgroundUrl));
            mvc.perform(get(profileUrl).session(returning)).andExpect(content().bytes(profile));
            mvc.perform(get(backgroundUrl).session(returning)).andExpect(content().bytes(background));
            assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider = ? AND provider_id = ?",
                    Integer.class, provider, "lifecycle"));
        }
    }

    @Test
    void accountsOnSameOrDifferentProvidersCannotReuseOtherAccountsImageUrlOrDeleteTheirData() throws Exception {
        var first = login("google", "first");
        String firstProfile = field(upload(first, "profile", png(0xFF0000)), "profileImageUrl");
        String firstBackground = field(upload(first, "background", png(0x0000FF)), "backgroundImageUrl");
        for (String provider : List.of("google", "kakao")) {
            var second = login(provider, "second");
            mvc.perform(get("/api/users/me").session(second))
                    .andExpect(jsonPath("$.profileImageUrl").value("https://example.com/social.png"))
                    .andExpect(jsonPath("$.backgroundImageUrl").isEmpty());
            for (String kind : List.of("profile", "background"))
                mvc.perform(get("/api/users/me/images/" + kind).session(second)).andExpect(status().isNotFound());
            upload(second, "profile", png(0x00FF00));
            upload(second, "background", png(0xFFFF00));
            mvc.perform(get(firstProfile).session(second)).andExpect(status().isNotFound());
            mvc.perform(get(firstBackground).session(second)).andExpect(status().isNotFound());
            for (String kind : List.of("profile", "background"))
                mvc.perform(delete("/api/users/me/images/" + kind).session(second).header("X-CSRF-TOKEN", csrf(second)))
                        .andExpect(status().isNoContent());
        }
        var returning = login("google", "first");
        mvc.perform(get(firstProfile).session(returning)).andExpect(content().bytes(png(0xFF0000)));
        mvc.perform(get(firstBackground).session(returning)).andExpect(content().bytes(png(0x0000FF)));
    }

    @Test
    void replacementRejectsStaleRevisionAndDeletionRestoresSocialFallback() throws Exception {
        var session = login("google", "replace");
        Long id = members.findByProviderAndProviderId("google", "replace").orElseThrow().getId();
        String oldUrl = field(upload(session, "profile", png(1)), "profileImageUrl");
        String newUrl = field(upload(session, "profile", png(2)), "profileImageUrl");
        assertNotEquals(oldUrl, newUrl);
        mvc.perform(get(oldUrl).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(newUrl).session(session)).andExpect(content().bytes(png(2)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM member_personal_images WHERE member_id = ?",
                Integer.class, id));
        upload(session, "background", png(3));
        complete(session);
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.backgroundImageUrl").isNotEmpty());
        mvc.perform(delete("/api/users/me/images/profile").session(session).header("X-CSRF-TOKEN", csrf(session)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").session(session))
                .andExpect(jsonPath("$.profileImageUrl").value("https://example.com/social.png"))
                .andExpect(jsonPath("$.backgroundImageUrl").isNotEmpty());
        mvc.perform(delete("/api/users/me/images/background").session(session).header("X-CSRF-TOKEN", csrf(session)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.backgroundImageUrl").isEmpty());
    }

    @Test
    void sameEmailAndProviderIdentityAcrossGoogleAndKakaoStillCreatesSeparateMembers() {
        login("google", "same-id"); login("kakao", "same-id");
        assertNotEquals(members.findByProviderAndProviderId("google", "same-id").orElseThrow().getId(),
                members.findByProviderAndProviderId("kakao", "same-id").orElseThrow().getId());
    }

    @Test
    void anonymousAndMissingOrExpiredCsrfRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/users/me/images/profile")).andExpect(status().isUnauthorized());
        var session = login("google", "csrf");
        mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/images/profile")
                .file(new MockMultipartFile("file", "photo.png", "image/png", png(1))).session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("invalid_csrf"));
        mvc.perform(delete("/api/users/me/images/profile").session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").isNotEmpty());
        String expired = csrf(session);
        mvc.perform(post("/api/logout").session(session).header("X-CSRF-TOKEN", expired)).andExpect(status().isNoContent());
        var returning = login("google", "csrf");
        mvc.perform(delete("/api/users/me/images/profile").session(returning).header("X-CSRF-TOKEN", expired))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidFileContentKindsSizeAndDimensionsAreRejected() throws Exception {
        var session = login("google", "invalid");
        for (byte[] bytes : List.of(new byte[0], "<svg/>".getBytes(StandardCharsets.UTF_8)))
            mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/images/profile")
                    .file(new MockMultipartFile("file", "fake.png", "image/png", bytes))
                    .session(session).header("X-CSRF-TOKEN", csrf(session))).andExpect(status().isBadRequest());
        mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/images/profile")
                .file(new MockMultipartFile("file", "big.png", "image/png", new byte[5 * 1024 * 1024 + 1]))
                .session(session).header("X-CSRF-TOKEN", csrf(session))).andExpect(status().is(413));
        var wide = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream(); ImageIO.write(wide, "png", output);
        mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/images/profile")
                .file(new MockMultipartFile("file", "wide.png", "image/png", output.toByteArray()))
                .session(session).header("X-CSRF-TOKEN", csrf(session))).andExpect(status().isBadRequest());
        mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/images/other")
                .file(new MockMultipartFile("file", "photo.png", "image/png", png(1)))
                .session(session).header("X-CSRF-TOKEN", csrf(session))).andExpect(status().isNotFound());
        mvc.perform(get("/api/users/me/images/profile").session(session)).andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedImagesAndProfileRemainAfterNewLoginTransaction() throws Exception {
        String identity = "committed-" + java.util.UUID.randomUUID();
        try {
            var first = login("google", identity); complete(first);
            Long id = members.findByProviderAndProviderId("google", identity).orElseThrow().getId();
            String profile = field(upload(first, "profile", png(0x00FF00)), "profileImageUrl");
            String background = field(upload(first, "background", png(0x0000FF)), "backgroundImageUrl");
            mvc.perform(post("/api/logout").session(first).header("X-CSRF-TOKEN", csrf(first)))
                    .andExpect(status().isNoContent());
            var returning = login("google", identity);
            mvc.perform(get("/api/users/me").session(returning))
                    .andExpect(jsonPath("$.userId").value(id)).andExpect(jsonPath("$.nickname").value("Chosen"))
                    .andExpect(jsonPath("$.birthYear").value(2003)).andExpect(jsonPath("$.profileCompleted").value(true));
            mvc.perform(get(profile).session(returning)).andExpect(content().bytes(png(0x00FF00)));
            mvc.perform(get(background).session(returning)).andExpect(content().bytes(png(0x0000FF)));
        } finally {
            jdbc.update("DELETE FROM members WHERE provider = ? AND provider_id = ?", "google", identity);
        }
    }
}
