package com.ggmount;

import com.ggmount.global.auth.oauth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verifies the existing owned-activity endpoint used as the FE statistics source. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:statistics_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:statistics_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password="
})
@Transactional
class HikingStatisticsSourceIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    long mountain;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
        mountain=jdbc.queryForObject("select min(id) from mountains",Long.class);
        for(long id:List.of(1001L,1002L)) jdbc.update("insert into members(id,provider,provider_id,nickname,profile_completed) values(?,'google',?,?,true)", id,"stats-"+id,"Stats "+id);
    }
    MockHttpSession login(long id) {
        var info=new OAuthUserInfo("google","stats-"+id,"stats@example.invalid","Stats "+id,null);
        var principal=new OAuthPrincipal(List.of(),Map.of("sub",info.providerId()),"sub",info);
        var security=SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal,principal.getAuthorities(),"google"));
        var session=new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,security);
        return session;
    }
    void activity(long id,long owner,double distance,boolean completed,String start) {
        jdbc.update("insert into hiking_activities(id,member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id) values(?,?,?,'Stats mountain',?,?,?,0,?,?)",
            id,owner,mountain,java.time.OffsetDateTime.parse(start),java.time.OffsetDateTime.parse(start),distance,completed,UUID.randomUUID().toString());
    }
    @Test void sourceRequiresLoginAndEmptyMemberReturnsEmptyList() throws Exception {
        mvc.perform(get("/api/users/me/hiking-records")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/hiking-records").session(login(1001))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void unjournaledAndUncertifiedActivitiesAreReturnedOnlyForTheSessionOwner() throws Exception {
        activity(2001,1001,1234.5,true,"2026-10-31T14:59:59Z");
        activity(2002,1001,0,false,"2026-10-31T15:00:00Z");
        activity(2003,1002,999999,true,"2026-11-01T00:00:00Z");
        mvc.perform(get("/api/users/me/hiking-records").session(login(1001)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(2002)).andExpect(jsonPath("$[0].distanceMeters").value(0))
            .andExpect(jsonPath("$[0].completed").value(false)).andExpect(jsonPath("$[0].journalId").isEmpty())
            .andExpect(jsonPath("$[1].id").value(2001)).andExpect(jsonPath("$[1].distanceMeters").value(1234.5))
            .andExpect(jsonPath("$[1].completed").value(true));
        mvc.perform(get("/api/users/me/hiking-records").session(login(1002)))
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(2003));
    }
    @Test void journalPublicationEditsAndDeletionLeaveActivityMetricsUnchanged() throws Exception {
        activity(2001,1001,1234.5,true,"2026-11-01T00:00:00Z");
        jdbc.update("insert into hiking_records(id,member_id,mountain_name,title,content,hiking_date,is_public,hiking_activity_id) values(3001,1001,'Stats mountain','Before','Text','2026-12-01',false,2001)");
        var session=login(1001);
        String csrf=mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
        mvc.perform(patch("/api/journals/3001").session(session).header("X-CSRF-TOKEN",csrf).contentType("application/json")
            .content("{\"title\":\"Changed\",\"content\":\"Edited\",\"isPublic\":true}")).andExpect(status().isOk());
        mvc.perform(delete("/api/journals/3001").session(session).header("X-CSRF-TOKEN",csrf)).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me/hiking-records").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].distanceMeters").value(1234.5)).andExpect(jsonPath("$[0].completed").value(true))
            .andExpect(jsonPath("$[0].journalId").isEmpty());
        assertEquals(0L,jdbc.queryForObject("select count(*) from hiking_records where member_id=1001",Long.class));
    }
}
