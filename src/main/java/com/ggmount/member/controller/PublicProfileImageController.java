package com.ggmount.member.controller;

import com.ggmount.member.service.PersonalDataService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * 숫자로 된 내부 회원 ID 경로로 본인 전용 이미지 경로와 구분하는 공개 프로필 이미지 조회 전용 API다.
 * 보안 설정에 따라 인증이 필요하며, 다른 회원의 프로필 이미지도 조회할 수 있지만 배경 이미지는 제공하지 않는다.
 */
@RestController
public class PublicProfileImageController {
    private final PersonalDataService service;
    public PublicProfileImageController(PersonalDataService service) { this.service=service; }
    // v는 필수이며, 서비스는 대상 회원이 없거나 버전이 비어 있거나 현재 이미지와 다르면 404로 거부한다.
    // 대상 회원의 프로필 설정이 미완료이면 서비스에서 403으로 거부한다.
    @GetMapping("/api/users/{userId:[0-9]+}/images/profile")
    public ResponseEntity<byte[]> profile(@PathVariable Long userId, @RequestParam("v") String revision) {
        var image=service.publicProfileImage(userId, revision);
        // 저장된 미디어 유형과 이미지 바이트를 반환하고, 캐시 저장과 브라우저의 형식 추측을 제한한다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(image.mediaType()))
            .header("X-Content-Type-Options", "nosniff").body(image.content());
    }
}
