package com.ggmount;
import com.ggmount.global.auth.oauth.*;
import com.ggmount.member.domain.Member;
import com.ggmount.member.service.OAuthMemberService;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.dto.MemberUpdateRequest;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import java.time.Year;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @Transactional
class ProfileJournalIntegrationTests {
 @Autowired WebApplicationContext context;
 @Autowired FilterChainProxy filters;
 @Autowired OAuthMemberService oauth;
 @Autowired MemberRepository members;
 @Autowired Validator validator;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired jakarta.persistence.EntityManager entityManager;
 MockMvc mvc;
 @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build(); }
 OAuthUserInfo info(String id) { return new OAuthUserInfo("google",id,"private@example.com","Social name","https://example.com/p.png"); }
 MockHttpSession session(String id) {
  var p=new OAuthPrincipal(List.of(),Map.of("sub",id),"sub",info(id));
  var security=SecurityContextHolder.createEmptyContext();
  security.setAuthentication(new OAuth2AuthenticationToken(p,p.getAuthorities(),"google"));
  var session=new MockHttpSession(); session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,security); return session;
 }
 String csrf(MockHttpSession session) throws Exception {
  String json=mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  return json.split("\"token\":\"")[1].split("\"")[0];
 }
 @Test void newAndReturningLoginPreserveCompletedProfileAndCalculateAge() throws Exception {
  Member m=oauth.processLogin(info("one")); assertFalse(m.isProfileCompleted());
  var session=session("one");
  mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.profileCompleted").value(false));
  mvc.perform(patch("/api/users/me/profile").session(session).header("X-CSRF-TOKEN",csrf(session)).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"Trail user\",\"birthYear\":2003}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.profileCompleted").value(true)).andExpect(jsonPath("$.age").value(Year.now().getValue()-2003+1));
  Member returning=oauth.processLogin(info("one")); assertEquals(m.getId(),returning.getId()); assertEquals("Trail user",returning.getNickname()); assertTrue(returning.isProfileCompleted());
 }
 @Test void validatesYearAndNicknameAndRejectsInvalidApiRequests() throws Exception {
  assertFalse(validator.validate(new MemberUpdateRequest("a",1900)).size()>0);
  assertFalse(validator.validate(new MemberUpdateRequest("a",Year.now().getValue())).size()>0);
  for(Integer year:Arrays.asList(null,1899,Year.now().getValue()+1)) assertFalse(validator.validate(new MemberUpdateRequest("a",year)).isEmpty());
  assertFalse(validator.validate(new MemberUpdateRequest(" ",2003)).isEmpty());
  oauth.processLogin(info("invalid")); var session=session("invalid"); String token=csrf(session);
  for(String year:List.of(String.valueOf(Year.now().getValue()+1),"1899","2003.5","\"not-number\"","null"))
   mvc.perform(patch("/api/users/me/profile").session(session).header("X-CSRF-TOKEN",token).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"a\",\"birthYear\":"+year+"}" )).andExpect(status().isBadRequest());
 }
 @Test void privacyOwnershipAndRankingUseRealMemberIds() throws Exception {
  Member owner=oauth.processLogin(info("owner")); owner.completeProfile("Owner",2003);
  Member viewer=oauth.processLogin(info("viewer")); viewer.completeProfile("Viewer",2000); members.flush();
  var ownerSession=session("owner"); var viewerSession=session("viewer"); String token=csrf(ownerSession);
  String privateResponse="";
  for(boolean visible:List.of(true,false)) {
   String json="{\"mountainName\":\"Mountain\",\"title\":\""+(visible?"Public":"Private")+"\",\"content\":\"Journal text\",\"hikingDate\":\"2020-01-01\",\"isPublic\":"+visible+"}";
   var result=mvc.perform(post("/api/journals").session(ownerSession).header("X-CSRF-TOKEN",token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk()).andReturn();
   if(!visible) privateResponse=result.getResponse().getContentAsString();
  }
  mvc.perform(get("/api/users/me/journals").session(ownerSession)).andExpect(jsonPath("$.length()").value(2));
  mvc.perform(get("/api/users/"+owner.getId()+"/journals").session(viewerSession)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].title").value("Public"));
  mvc.perform(get("/api/journals").session(viewerSession)).andExpect(jsonPath("$.length()").value(1));
  mvc.perform(get("/api/users/"+owner.getId()+"/profile").session(viewerSession)).andExpect(jsonPath("$.userId").value(owner.getId())).andExpect(jsonPath("$.birthYear").doesNotExist()).andExpect(jsonPath("$.age").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.provider").doesNotExist());
  mvc.perform(get("/api/rankings").session(viewerSession)).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].userId").value(owner.getId())).andExpect(jsonPath("$[0].score").isEmpty());
  String id=privateResponse.split("\"id\":")[1].split(",")[0];
  String update="{\"mountainName\":\"Mountain\",\"title\":\"Changed\",\"content\":\"text\",\"hikingDate\":\"2020-01-01\",\"isPublic\":true}";
  mvc.perform(patch("/api/journals/"+id).session(viewerSession).header("X-CSRF-TOKEN",csrf(viewerSession)).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isNotFound());
  mvc.perform(patch("/api/journals/"+id).session(ownerSession).header("X-CSRF-TOKEN",token).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk());
  mvc.perform(get("/api/users/"+owner.getId()+"/journals").session(viewerSession)).andExpect(jsonPath("$.length()").value(2));
 }
 @Test void oauthIdentityUsesProviderAndIdRatherThanEmail() {
  Member google=oauth.processLogin(info("same"));
  Member kakao=oauth.processLogin(new OAuthUserInfo("kakao","same","private@example.com","Kakao",null));
  assertNotEquals(google.getId(),kakao.getId());
  kakao.completeProfile("Chosen nickname",2003);
  assertEquals("Chosen nickname",oauth.processLogin(new OAuthUserInfo("kakao","same",null,"Changed social nickname",null)).getNickname());
 }
 @Test void rankingOwnAndPublicProfileUseSameStoredScore() throws Exception {
  Member m=oauth.processLogin(info("score")); m.completeProfile("Scored",2003); members.flush();
  Long id=m.getId();
  jdbc.update("update members set score=1250 where id=?",id); entityManager.clear();
  var session=session("score");
  mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.score").value(1250));
  mvc.perform(get("/api/users/"+id+"/profile").session(session)).andExpect(jsonPath("$.score").value(1250));
  mvc.perform(get("/api/rankings").session(session)).andExpect(jsonPath("$[0].score").value(1250)).andExpect(jsonPath("$[0].userId").value(id));
 }
 @Test void csrfAndAuthenticationRemainRequired() throws Exception {
  mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
  oauth.processLogin(info("csrf"));
  mvc.perform(patch("/api/users/me/profile").session(session("csrf")).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"a\",\"birthYear\":2003}")).andExpect(status().isForbidden());
 }
}
