package com.ggmount;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:mountain_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:mountain_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password="
})
class MountainDataContractTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    MockHttpSession session;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
        var info = new OAuthUserInfo("google", "data-contract", "contract@example.invalid", "검증", null);
        if (jdbc.queryForObject("select count(*) from members where provider='google' and provider_id='data-contract'",Long.class)==0)
            jdbc.update("insert into members(provider,provider_id,nickname,profile_completed) values('google','data-contract','Contract',true)");
        long memberId=jdbc.queryForObject("select id from members where provider='google' and provider_id='data-contract'",Long.class);
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", info.providerId()), "sub", info, memberId);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
    }

    String response(String url) throws Exception {
        return mvc.perform(get(url).session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void catalogAndAllCourseResponsesKeepSeedIdsAndDifficulty() throws Exception {
        var parser = JsonParserFactory.getJsonParser();
        var mountains = parser.parseList(response("/api/mountains"));
        assertEquals(140, mountains.size());
        Set<Long> mountainIds = new HashSet<>(), courseIds = new HashSet<>();
        for (Object item : mountains) {
            Map<?, ?> mountain = (Map<?, ?>) item;
            long mountainId = ((Number) mountain.get("id")).longValue();
            assertTrue(mountainIds.add(mountainId));
            assertFalse(mountain.containsKey("difficulty"));
            var courses = parser.parseList(response("/api/mountains/" + mountainId + "/courses"));
            assertEquals(((Number) mountain.get("courseCount")).intValue(), courses.size());
            for (Object entry : courses) {
                Map<?, ?> course = (Map<?, ?>) entry;
                long courseId = ((Number) course.get("id")).longValue();
                assertTrue(courseIds.add(courseId));
                assertEquals(mountainId, ((Number) course.get("mountainId")).longValue());
                assertEquals(jdbc.queryForObject("select difficulty from courses where id=?", String.class, courseId), course.get("difficulty"));
                var detail = parser.parseMap(response("/api/courses/" + courseId));
                assertEquals(course, detail.get("course"));
                List<?> path = (List<?>) detail.get("path");
                List<?> first = (List<?>) path.getFirst();
                assertEquals(((Number) course.get("startLng")).doubleValue(), ((Number) first.getFirst()).doubleValue(), 1e-5);
                assertEquals(((Number) course.get("startLat")).doubleValue(), ((Number) first.get(1)).doubleValue(), 1e-5);
            }
        }
        assertEquals(140, mountainIds.size());
        assertFalse(mountainIds.contains(112L));
        assertEquals(420, courseIds.size());
        for (long id = 1; id <= 420; id++) assertTrue(courseIds.contains(id));
    }

    @Test void absentMountainAndEmptyCoursesRemainDistinct() throws Exception {
        long emptyId = jdbc.queryForObject("select min(id) from mountains where id not in (select mountain_id from courses)", Long.class);
        assertEquals(List.of(), JsonParserFactory.getJsonParser().parseList(response("/api/mountains/" + emptyId + "/courses")));
        mvc.perform(get("/api/mountains/112/courses").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/courses/999999").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/mountains")).andExpect(status().isUnauthorized());
    }
}
