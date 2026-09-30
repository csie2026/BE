package com.ggmount.member.service;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.domain.Member;
import com.ggmount.member.dto.*;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service @Transactional(readOnly=true)
public class MemberService {
 private final MemberRepository repository;
 public MemberService(MemberRepository repository) { this.repository=repository; }
 public Member current(OAuthPrincipal principal) {
  if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  var info=principal.getUserInfo();
  return repository.findByProviderAndProviderId(info.provider(),info.providerId()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
 }
 public Member find(Long id) { return repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)); }
 @Transactional public MemberResponse update(OAuthPrincipal p,MemberUpdateRequest request) {
  Member member=current(p); member.completeProfile(request.nickname(),request.birthYear()); return MemberResponse.from(member);
 }
 public void requireComplete(Member m) { if(!m.isProfileCompleted()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"프로필 설정이 필요합니다."); }
}
