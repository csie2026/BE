package com.ggmount.hiking.controller;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.hiking.dto.*;
import com.ggmount.hiking.service.HikingRecordService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api")
public class HikingRecordController {
 private final HikingRecordService service;
 public HikingRecordController(HikingRecordService service) { this.service=service; }
 @GetMapping("/journals") public List<HikingRecordResponse> feed() { return service.publicRecords(null); }
 @GetMapping("/users/me/journals") public List<HikingRecordResponse> mine(@AuthenticationPrincipal OAuthPrincipal p) { return service.mine(p); }
 @GetMapping("/users/{id}/journals") public List<HikingRecordResponse> user(@PathVariable Long id) { return service.publicRecords(id); }
 @PostMapping("/journals") public HikingRecordResponse create(@AuthenticationPrincipal OAuthPrincipal p,@Valid @RequestBody HikingRecordRequest r) { return service.save(p,null,r); }
 @PatchMapping("/journals/{id}") public HikingRecordResponse update(@AuthenticationPrincipal OAuthPrincipal p,@PathVariable Long id,@Valid @RequestBody HikingRecordRequest r) { return service.save(p,id,r); }
}
