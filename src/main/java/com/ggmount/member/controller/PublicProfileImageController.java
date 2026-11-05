package com.ggmount.member.controller;

import com.ggmount.member.service.PersonalDataService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Numeric IDs keep this GET-only route distinct from /users/me/images/{kind}. */
@RestController
public class PublicProfileImageController {
    private final PersonalDataService service;
    public PublicProfileImageController(PersonalDataService service) { this.service=service; }
    @GetMapping("/api/users/{userId:[0-9]+}/images/profile")
    public ResponseEntity<byte[]> profile(@PathVariable Long userId, @RequestParam("v") String revision) {
        var image=service.publicProfileImage(userId, revision);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(image.mediaType()))
            .header("X-Content-Type-Options", "nosniff").body(image.content());
    }
}
