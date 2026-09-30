package com.ggmount.member.service;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import com.ggmount.member.domain.Member;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OAuthMemberService {
 private final MemberRepository repository;
 public OAuthMemberService(MemberRepository repository) { this.repository=repository; }
 @Transactional public Member processLogin(OAuthUserInfo info) {
  Member member=repository.findByProviderAndProviderId(info.provider(),info.providerId()).orElseGet(()->new Member(info));
  member.refreshOAuth(info); return repository.save(member);
 }
}
