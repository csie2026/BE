package com.ggmount.member.controller;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.*;
import com.ggmount.member.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/users")
public class MemberController {
 private final MemberService service;
 public MemberController(MemberService service) { this.service=service; }
 @GetMapping("/me") public MemberResponse me(@AuthenticationPrincipal OAuthPrincipal p) { return MemberResponse.from(service.current(p)); }
 @PatchMapping("/me/profile") public MemberResponse update(@AuthenticationPrincipal OAuthPrincipal p,@Valid @RequestBody MemberUpdateRequest request) { return service.update(p,request); }
 @GetMapping("/{id}/profile") public PublicMemberResponse profile(@PathVariable Long id) { var m=service.find(id); service.requireComplete(m); return PublicMemberResponse.from(m); }
}
