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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ranking_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.url=jdbc:h2:mem:ranking_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password="
})
@Transactional
class RankingIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }
    void member(long id, Long score, boolean complete) {
        jdbc.update("insert into members(id,provider,provider_id,nickname,profile_completed,score) values(?,'google',?,?,?,?)",
            id, "rank-" + id, "Rank " + id, complete, score);
    }
    MockHttpSession login(long id) {
        var info = new OAuthUserInfo("google", "rank-" + id, "ranking@example.invalid", "Rank " + id, null);
        var principal = new OAuthPrincipal(List.of(), Map.of("sub", info.providerId()), "sub", info);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }
    @Test void requiresAuthenticationForBothRankingEndpoints() throws Exception {
        mvc.perform(get("/api/rankings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rankings/summary")).andExpect(status().isUnauthorized());
    }
    @Test void emptyParticipantsHaveNoInventedScoreOrRank() throws Exception {
        member(1001,null,false);
        mvc.perform(get("/api/rankings/summary").session(login(1001)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.members").isEmpty())
            .andExpect(jsonPath("$.mine").isEmpty()).andExpect(jsonPath("$.totalMembers").value(0));
    }
    @Test void ordinalTiesUseMemberIdAndNullSortsAfterRealZero() throws Exception {
        member(1001,50L,true);member(1002,50L,true);member(1003,0L,true);member(1004,null,true);
        member(1005,9999L,false);
        mvc.perform(get("/api/rankings/summary").session(login(1004)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.totalMembers").value(4))
            .andExpect(jsonPath("$.members[0].userId").value(1001)).andExpect(jsonPath("$.members[0].rank").value(1))
            .andExpect(jsonPath("$.members[1].userId").value(1002)).andExpect(jsonPath("$.members[1].rank").value(2))
            .andExpect(jsonPath("$.members[2].score").value(0))
            .andExpect(jsonPath("$.members[3].score").isEmpty()).andExpect(jsonPath("$.mine.rank").value(4))
            .andExpect(jsonPath("$.mine.email").doesNotExist()).andExpect(jsonPath("$.mine.birthYear").doesNotExist());
        mvc.perform(get("/api/rankings").session(login(1004)))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].userId").value(1001))
            .andExpect(jsonPath("$[1].userId").value(1002)).andExpect(jsonPath("$[2].score").value(0))
            .andExpect(jsonPath("$[3].score").isEmpty());
    }
    @Test void ownRankIsReturnedBeyondTop100IncludingUncalculatedMembers() throws Exception {
        for(int i=1;i<=104;i++) member(1000+i,(long)(105-i),true);
        member(1105,null,true);
        mvc.perform(get("/api/rankings/summary").session(login(1105)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(100))
            .andExpect(jsonPath("$.members[99].rank").value(100))
            .andExpect(jsonPath("$.totalMembers").value(105)).andExpect(jsonPath("$.mine.userId").value(1105))
            .andExpect(jsonPath("$.mine.rank").value(105)).andExpect(jsonPath("$.mine.score").isEmpty());
        jdbc.update("update members set score=1 where id=1105");
        entityManager.clear();
        mvc.perform(get("/api/rankings/summary").session(login(1105)))
            .andExpect(jsonPath("$.mine.rank").value(105)).andExpect(jsonPath("$.mine.score").value(1));
    }
    @Test void refreshReordersChangedScoresAndAllProfileApisReadTheSameValue() throws Exception {
        member(1001,50L,true);member(1002,0L,true);
        var session=login(1002);
        mvc.perform(get("/api/rankings/summary").session(session)).andExpect(jsonPath("$.mine.rank").value(2));
        jdbc.update("update members set score=270 where id=1002");
        entityManager.clear();
        mvc.perform(get("/api/rankings/summary").session(session)).andExpect(jsonPath("$.mine.rank").value(1))
            .andExpect(jsonPath("$.mine.score").value(270)).andExpect(jsonPath("$.members[0].userId").value(1002));
        mvc.perform(get("/api/rankings").session(session)).andExpect(jsonPath("$[0].score").value(270));
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.score").value(270));
        mvc.perform(get("/api/users/1002/profile").session(session)).andExpect(jsonPath("$.score").value(270));
        jdbc.update("update members set score=0 where id=1002");
        entityManager.clear();
        mvc.perform(get("/api/rankings/summary").session(session)).andExpect(jsonPath("$.mine.rank").value(2))
            .andExpect(jsonPath("$.mine.score").value(0));
    }

    @Test void journalBonusGrantAndRevocationImmediatelyReachRankingSnapshot() throws Exception {
        member(1001,null,true);
        var session=login(1001);
        long mountain=jdbc.queryForObject("select min(id) from mountains",Long.class);
        String key=UUID.randomUUID().toString();
        String csrf=mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
        String activity="""
            {"mountainId":%d,"courseId":null,"startedAt":"2020-01-01T01:00:00Z",
             "endedAt":"2020-01-01T01:00:00Z","distanceMeters":0,"elapsedMs":0,
             "completed":false,"clientRequestId":"%s","gpsSegments":[]}
            """.formatted(mountain,key);
        mvc.perform(post("/api/users/me/hiking-records").session(session).header("X-CSRF-TOKEN",csrf)
            .contentType("application/json").content(activity)).andExpect(status().isOk());
        long id=jdbc.queryForObject("select id from hiking_activities where member_id=1001 and client_request_id=?",Long.class,key);
        mvc.perform(post("/api/journals").session(session).header("X-CSRF-TOKEN",csrf)
            .contentType("application/json").content("{\"hikingRecordId\":"+id+",\"title\":\"Rank bonus\",\"content\":\"Test\",\"isPublic\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/rankings/summary").session(session)).andExpect(jsonPath("$.mine.score").value(50));
        long journal=jdbc.queryForObject("select id from hiking_records where hiking_activity_id=?",Long.class,id);
        mvc.perform(patch("/api/journals/"+journal).session(session).header("X-CSRF-TOKEN",csrf)
            .contentType("application/json").content("{\"title\":\"Rank bonus\",\"content\":\"Test\",\"isPublic\":false}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/rankings/summary").session(session)).andExpect(jsonPath("$.mine.score").value(0));
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.score").value(0));
    }
}
