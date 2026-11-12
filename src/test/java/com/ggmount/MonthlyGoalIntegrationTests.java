package com.ggmount;

import com.ggmount.global.auth.oauth.*;
import com.ggmount.member.dto.MonthlyGoalRequest;
import com.ggmount.member.service.MonthlyGoalService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 전용 H2와 실제 보안 필터를 사용한다. 일반 사례는 테스트 트랜잭션 안에서 실행하며 동시 저장·마이그레이션은 별도 경계를 사용한다.
@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:goals_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.flyway.url=jdbc:h2:mem:goals_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.flyway.user=sa","spring.flyway.password="
})
@Transactional
class MonthlyGoalIntegrationTests {
 static final String API="/api/users/me/monthly-goals/";
 @Autowired WebApplicationContext context;
 @Autowired FilterChainProxy filters;
 @Autowired JdbcTemplate jdbc;
 @Autowired MonthlyGoalService service;
 MockMvc mvc;
 @BeforeEach void setup(){
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
  for(long id:List.of(1001L,1002L))jdbc.update("insert into members(id,provider,provider_id,nickname,profile_completed,score) values(?,'google',?,?,true,777)",id,"goal-"+id,"Goal "+id);
 }
 OAuthPrincipal principal(long id){var info=new OAuthUserInfo("google","goal-"+id,"goal@example.invalid","Goal "+id,null);return new OAuthPrincipal(List.of(),Map.of("sub",info.providerId()),"sub",info,id);}
 MockHttpSession login(long id){var p=principal(id);var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(new OAuth2AuthenticationToken(p,p.getAuthorities(),"google"));var s=new MockHttpSession();s.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,security);return s;}
 String token(MockHttpSession session)throws Exception{return mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];}
 ResultActions putGoal(MockHttpSession session,String month,String body)throws Exception{return mvc.perform(put(API+month).session(session).header("X-CSRF-TOKEN",token(session)).contentType("application/json").content(body));}
 @Test void authenticationAndCsrfAreRequired()throws Exception{
  mvc.perform(get(API+"2026-11")).andExpect(status().isUnauthorized());
  mvc.perform(put(API+"2026-11").session(login(1001)).contentType("application/json").content("{\"distanceKm\":10,\"hikeCount\":4}")).andExpect(status().isForbidden());
 }
 @Test void unsetGoalsReturnTheOwnerAndMonthWithoutInventedTargets()throws Exception{
  mvc.perform(get(API+"2026-11").session(login(1001))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.userId").value(1001)).andExpect(jsonPath("$.month").value("2026-11"))
   .andExpect(jsonPath("$.distanceKm").isEmpty()).andExpect(jsonPath("$.hikeCount").isEmpty()).andExpect(jsonPath("$.email").doesNotExist());
 }
 @Test void independentTargetsCanBeCreatedUpdatedAndClearedIdempotently()throws Exception{
  var s=login(1001);
  putGoal(s,"2026-11","{\"distanceKm\":1.005,\"hikeCount\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.distanceKm").value(1.005)).andExpect(jsonPath("$.hikeCount").isEmpty());
  putGoal(s,"2026-11","{\"distanceKm\":null,\"hikeCount\":4}").andExpect(status().isOk()).andExpect(jsonPath("$.distanceKm").isEmpty()).andExpect(jsonPath("$.hikeCount").value(4));
  putGoal(s,"2026-11","{\"distanceKm\":10,\"hikeCount\":5}").andExpect(status().isOk());
  assertEquals(1L,jdbc.queryForObject("select count(*) from member_monthly_goals where member_id=1001",Long.class));
  for(int i=0;i<2;i++)putGoal(s,"2026-11","{\"distanceKm\":null,\"hikeCount\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.distanceKm").isEmpty()).andExpect(jsonPath("$.hikeCount").isEmpty());
  assertEquals(0L,jdbc.queryForObject("select count(*) from member_monthly_goals where member_id=1001",Long.class));
 }
 @Test void ownerComesFromSessionAndAAndBGoalsStaySeparate()throws Exception{
  putGoal(login(1001),"2026-11?memberId=1002","{\"distanceKm\":10,\"hikeCount\":4}").andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(1001));
  mvc.perform(get(API+"2026-11").session(login(1002))).andExpect(jsonPath("$.distanceKm").isEmpty());
  putGoal(login(1002),"2026-11","{\"distanceKm\":20,\"hikeCount\":8}").andExpect(status().isOk());
  mvc.perform(get(API+"2026-11").session(login(1001))).andExpect(jsonPath("$.distanceKm").value(10));
  mvc.perform(get(API+"2026-11").session(login(1002))).andExpect(jsonPath("$.distanceKm").value(20));
 }
 @Test void monthsAndYearsDoNotCarryForwardAndClearingCurrentKeepsPrevious()throws Exception{
  var s=login(1001);putGoal(s,"2026-12","{\"distanceKm\":10,\"hikeCount\":4}").andExpect(status().isOk());
  mvc.perform(get(API+"2027-01").session(s)).andExpect(jsonPath("$.distanceKm").isEmpty());
  putGoal(s,"2027-01","{\"distanceKm\":20,\"hikeCount\":8}").andExpect(status().isOk());
  putGoal(s,"2027-01","{\"distanceKm\":null,\"hikeCount\":null}").andExpect(status().isOk());
  mvc.perform(get(API+"2026-12").session(s)).andExpect(jsonPath("$.distanceKm").value(10)).andExpect(jsonPath("$.hikeCount").value(4));
 }
 @Test void invalidMonthsAndTargetsDoNotCreateRows()throws Exception{
  var s=login(1001);
  for(String month:List.of("2026-13","2026-00","2026-1","1899-11","not-a-month"))mvc.perform(get(API+month).session(s)).andExpect(status().isBadRequest());
  for(String json:List.of("{\"distanceKm\":0}","{\"distanceKm\":1000.001}","{\"distanceKm\":1.0001}","{\"hikeCount\":0}","{\"hikeCount\":1001}","{\"hikeCount\":1.5}"))putGoal(s,"2026-11",json).andExpect(status().isBadRequest());
  assertEquals(0L,jdbc.queryForObject("select count(*) from member_monthly_goals",Long.class));
 }
 @Test void editingGoalsCannotChangeActivitiesJournalsOrScore()throws Exception{
  long mountain=jdbc.queryForObject("select min(id) from mountains",Long.class);
  jdbc.update("insert into hiking_activities(id,member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id,gps_validation_version,request_fingerprint_version,request_fingerprint) values(2001,1001,?,'Goal test','2026-11-01T00:00:00Z','2026-11-01T01:00:00Z',1234.5,3600000,true,?,null,null,null)",mountain,UUID.randomUUID().toString());
  jdbc.update("insert into hiking_records(id,member_id,mountain_name,title,content,hiking_date,is_public,hiking_activity_id) values(3001,1001,'Goal test','Unchanged','Text','2026-11-01',false,2001)");
  var s=login(1001);String before=mvc.perform(get("/api/users/me/hiking-records").session(s)).andReturn().getResponse().getContentAsString();
  putGoal(s,"2026-11","{\"distanceKm\":100,\"hikeCount\":20}").andExpect(status().isOk());
  putGoal(s,"2026-11","{\"distanceKm\":null,\"hikeCount\":null}").andExpect(status().isOk());
  assertEquals(before,mvc.perform(get("/api/users/me/hiking-records").session(s)).andReturn().getResponse().getContentAsString());
  assertEquals("Unchanged",jdbc.queryForObject("select title from hiking_records where id=3001",String.class));
  assertEquals(777L,jdbc.queryForObject("select score from members where id=1001",Long.class));
 }
 @Test void concurrentFirstSavesProduceOneWholeMemberMonthRow()throws Exception{
  // 회원 자료를 먼저 커밋해 작업 스레드에 보이게 하고 각 서비스 트랜잭션을 경쟁시킨다. 어느 요청이 마지막일지는 고정하지 않는다.
  TestTransaction.flagForCommit();TestTransaction.end();var pool=Executors.newFixedThreadPool(2);
  try{var gate=new CountDownLatch(1);
   var a=pool.submit(()->{gate.await();return service.save(principal(1001),"2026-11",new MonthlyGoalRequest(new BigDecimal("10"),4));});
   var b=pool.submit(()->{gate.await();return service.save(principal(1001),"2026-11",new MonthlyGoalRequest(new BigDecimal("20"),8));});
   gate.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
   assertEquals(1L,jdbc.queryForObject("select count(*) from member_monthly_goals where member_id=1001",Long.class));
   var result=service.get(principal(1001),"2026-11");assertTrue((result.distanceKm().compareTo(new BigDecimal("10"))==0&&result.hikeCount()==4)||(result.distanceKm().compareTo(new BigDecimal("20"))==0&&result.hikeCount()==8));
  }finally{pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);jdbc.update("delete from member_monthly_goals where member_id in (1001,1002)");jdbc.update("delete from members where id in (1001,1002)");TestTransaction.start();}
 }
 @Test void migrationKeepsExistingGoalsAndAllowsOneTargetOnly()throws Exception{
  // 매번 별도 H2에 V5까지 적재한 뒤 최신 마이그레이션을 적용해 기존 값 보존과 선택적 목표 제약을 검사한다.
  String url="jdbc:h2:mem:goal_upgrade_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(url,"sa","").target("5").load().migrate();
  try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()){
   q.executeUpdate("insert into members(id,provider,provider_id,nickname,profile_completed) values(1001,'google','legacy-goal','Legacy',true)");
   q.executeUpdate("insert into member_monthly_goals values(1001,'2026-10-01',80,4)");
  }
  Flyway.configure().dataSource(url,"sa","").load().migrate();
  try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()){
   try(var rs=q.executeQuery("select target_distance_km,target_hiking_count from member_monthly_goals where member_id=1001")){assertTrue(rs.next());assertEquals(80,rs.getBigDecimal(1).intValueExact());assertEquals(4,rs.getInt(2));}
   q.executeUpdate("insert into member_monthly_goals values(1001,'2026-11-01',10,null)");
   q.executeUpdate("insert into member_monthly_goals values(1001,'2026-12-01',null,5)");
   assertThrows(java.sql.SQLException.class,()->q.executeUpdate("insert into member_monthly_goals values(1001,'2027-01-01',null,null)"));
  }
 }
 @Test void incompleteProfilesCannotReadOrSaveGoals()throws Exception{
  jdbc.update("update members set profile_completed=false where id=1001");
  mvc.perform(get(API+"2026-11").session(login(1001))).andExpect(status().isForbidden());
  putGoal(login(1001),"2026-11","{\"distanceKm\":10,\"hikeCount\":4}").andExpect(status().isForbidden());
 }
}
