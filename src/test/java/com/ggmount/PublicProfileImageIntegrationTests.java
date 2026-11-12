package com.ggmount;

import com.ggmount.global.auth.oauth.*;
import com.ggmount.member.repository.MemberRepository;
import com.ggmount.member.service.OAuthMemberService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 전용 H2의 이미지 바이트·revision을 실제 서비스로 다루고, 모의 인증 세션을 통해 본인·공개 경로와 랭킹 응답을 대조한다.
@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:public_images_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.flyway.url=jdbc:h2:mem:public_images_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.flyway.user=sa","spring.flyway.password="
})
@Transactional
class PublicProfileImageIntegrationTests {
 @Autowired WebApplicationContext context;
 @Autowired FilterChainProxy filters;
 @Autowired OAuthMemberService oauth;
 @Autowired MemberRepository members;
 @Autowired JdbcTemplate jdbc;
 @Autowired EntityManager entityManager;
 MockMvc mvc;MockHttpSession owner,viewer;long ownerId,viewerId;
 @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();owner=login("owner","https://example.invalid/owner.png");viewer=login("viewer","https://example.invalid/viewer.png");ownerId=id("owner");viewerId=id("viewer");}
 long id(String identity){return members.findByProviderAndProviderId("google",identity).orElseThrow().getId();}
 MockHttpSession login(String identity,String image){var info=new OAuthUserInfo("google",identity,"private@example.invalid","Avatar "+identity,image);var member=oauth.processLogin(info);member.completeProfile("Avatar "+identity,2003);members.flush();var p=new OAuthPrincipal(List.of(),Map.of("sub",identity),"sub",info,member.getId());var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(new OAuth2AuthenticationToken(p,p.getAuthorities(),"google"));var s=new MockHttpSession();s.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,security);return s;}
 String token(MockHttpSession s)throws Exception{return field(mvc.perform(get("/api/csrf").session(s)).andReturn().getResponse().getContentAsString(),"token");}
 String field(String json,String key){return json.split("\""+key+"\":\"")[1].split("\"")[0];}
 byte[] png(int rgb)throws Exception{var image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);image.setRGB(1,1,rgb);var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();}
 String upload(MockHttpSession s,String kind,int rgb)throws Exception{return field(mvc.perform(multipart(HttpMethod.PUT,"/api/users/me/images/"+kind).file(new MockMultipartFile("file","image.png","image/png",png(rgb))).session(s).header("X-CSRF-TOKEN",token(s))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),kind.equals("profile")?"profileImageUrl":"backgroundImageUrl");}
 String publicUrl(long id){return "/api/users/"+id+"/images/profile?v="+jdbc.queryForObject("select revision from member_personal_images where member_id=? and kind='profile'",String.class,id);}
 void publicMetadata(long id,String expected)throws Exception{
  mvc.perform(get("/api/users/"+id+"/profile").session(viewer)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.profileImageUrl").value(expected));
  mvc.perform(get("/api/rankings").session(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.userId == "+id+")].profileImageUrl").value(org.hamcrest.Matchers.hasItem(expected)));
  mvc.perform(get("/api/rankings/summary").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.mine.profileImageUrl").value(expected));
 }
 @Test void ownedPublicAndBothRankingApisResolveTheSameUploadedRevision()throws Exception{
  String owned=upload(owner,"profile",0xff0000);upload(viewer,"profile",0x00ff00);String published=publicUrl(ownerId);
  mvc.perform(get("/api/users/me").session(owner)).andExpect(jsonPath("$.profileImageUrl").value(owned));publicMetadata(ownerId,published);
  mvc.perform(get(owned).session(owner)).andExpect(status().isOk()).andExpect(content().bytes(png(0xff0000)));
  mvc.perform(get(published).session(viewer)).andExpect(status().isOk()).andExpect(content().bytes(png(0xff0000)))
   .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
 }
 @Test void replacementInvalidatesOldOwnedAndPublicRevisionUrls()throws Exception{
  String first=upload(owner,"profile",1),oldPublic=publicUrl(ownerId);String second=upload(owner,"profile",2),newPublic=publicUrl(ownerId);
  assertNotEquals(first,second);assertNotEquals(oldPublic,newPublic);
  mvc.perform(get(first).session(owner)).andExpect(status().isNotFound());mvc.perform(get(oldPublic).session(viewer)).andExpect(status().isNotFound());
  mvc.perform(get(newPublic).session(viewer)).andExpect(content().bytes(png(2)));publicMetadata(ownerId,newPublic);
 }
 @Test void deletionRestoresOauthAcrossScreensAndPreservesPrivateBackground()throws Exception{
  upload(owner,"profile",1);String oldPublic=publicUrl(ownerId),background=upload(owner,"background",3);
  mvc.perform(delete("/api/users/me/images/profile").session(owner).header("X-CSRF-TOKEN",token(owner))).andExpect(status().isNoContent());
  mvc.perform(get(oldPublic).session(viewer)).andExpect(status().isNotFound());publicMetadata(ownerId,"https://example.invalid/owner.png");
  mvc.perform(get("/api/users/me").session(owner)).andExpect(jsonPath("$.profileImageUrl").value("https://example.invalid/owner.png")).andExpect(jsonPath("$.backgroundImageUrl").value(background));
  mvc.perform(get(background).session(owner)).andExpect(content().bytes(png(3)));mvc.perform(get(background).session(viewer)).andExpect(status().isNotFound());
 }
 @Test void publicProfileExposesNoBackgroundOrPrivateFieldsAndRequiresAuthentication()throws Exception{
  upload(owner,"profile",1);String bg=upload(owner,"background",2),pub=publicUrl(ownerId);
  mvc.perform(get(pub)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/users/"+ownerId+"/profile").session(viewer)).andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.birthYear").doesNotExist()).andExpect(jsonPath("$.age").doesNotExist()).andExpect(jsonPath("$.backgroundImageUrl").doesNotExist()).andExpect(jsonPath("$.provider").doesNotExist());
  mvc.perform(get("/api/users/"+ownerId+"/images/background?v="+bg.split("v=")[1]).session(viewer)).andExpect(status().isNotFound());
 }
 @Test void publicReadRouteCannotBeUsedToModifyOrDeleteAnotherMembersImage()throws Exception{
  upload(owner,"profile",1);String pub=publicUrl(ownerId);
  mvc.perform(delete(pub).session(viewer).header("X-CSRF-TOKEN",token(viewer))).andExpect(status().isMethodNotAllowed());
  mvc.perform(multipart(HttpMethod.PUT,pub).file(new MockMultipartFile("file","test.png","image/png",png(2))).session(viewer).header("X-CSRF-TOKEN",token(viewer))).andExpect(status().isMethodNotAllowed());
  upload(viewer,"profile",3);
  mvc.perform(delete("/api/users/me/images/profile?memberId="+ownerId).session(viewer).header("X-CSRF-TOKEN",token(viewer))).andExpect(status().isNoContent());
  mvc.perform(get(pub).session(viewer)).andExpect(content().bytes(png(1)));
 }
 @Test void ownImageOutsideTop100StillUsesUploadedMetadataWithoutChangingRanks()throws Exception{
  upload(owner,"profile",1);upload(owner,"background",2);
  for(int i=0;i<104;i++)jdbc.update("insert into members(id,provider,provider_id,nickname,profile_completed,score) values(?,'google',?,?,true,999)",10000+i,"top-"+i,"Top "+i);
  mvc.perform(get("/api/rankings/summary").session(owner)).andExpect(jsonPath("$.totalMembers").value(106)).andExpect(jsonPath("$.members.length()").value(100)).andExpect(jsonPath("$.mine.rank").value(105)).andExpect(jsonPath("$.mine.profileImageUrl").value(publicUrl(ownerId)));
 }
 @Test void noUploadKeepsOauthAndNoOauthReturnsNullInsteadOfAnotherMembersImage()throws Exception{
  publicMetadata(ownerId,"https://example.invalid/owner.png");
  jdbc.update("update members set profile_image_url=null where id=?",ownerId);entityManager.clear();
  mvc.perform(get("/api/users/me").session(owner)).andExpect(jsonPath("$.profileImageUrl").isEmpty());
  mvc.perform(get("/api/users/"+ownerId+"/profile").session(viewer)).andExpect(jsonPath("$.profileImageUrl").isEmpty());
  mvc.perform(get("/api/rankings/summary").session(owner)).andExpect(jsonPath("$.mine.profileImageUrl").isEmpty());
 }
 @Test void revisionsCannotBeReusedForAnotherIdAndIncompleteProfilesStayPrivate()throws Exception{
  upload(owner,"profile",1);upload(viewer,"profile",2);String pub=publicUrl(ownerId),rev=pub.split("v=")[1];
  mvc.perform(get("/api/users/"+viewerId+"/images/profile?v="+rev).session(owner)).andExpect(status().isNotFound());
  mvc.perform(get("/api/users/999999/images/profile?v="+rev).session(owner)).andExpect(status().isNotFound());
  jdbc.update("update members set profile_completed=false where id=?",ownerId);entityManager.clear();
  mvc.perform(get(pub).session(viewer)).andExpect(status().isForbidden());
 }
}
