package com.ggmount.member.controller;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.MemberResponse;
import com.ggmount.member.service.PersonalDataService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users/me/images")
public class PersonalDataController {
    private final PersonalDataService service;

    public PersonalDataController(PersonalDataService service) {
        this.service = service;
    }

    @GetMapping("/{kind}")
    public ResponseEntity<byte[]> image(
        @AuthenticationPrincipal OAuthPrincipal principal,
        @PathVariable String kind,
        @RequestParam(value = "v", required = false) String revision
    ) {
        var image = service.image(principal, kind, revision);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(image.mediaType()))
            .header("X-Content-Type-Options", "nosniff").body(image.content());
    }

    @PutMapping(value = "/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MemberResponse> saveImage(
        @AuthenticationPrincipal OAuthPrincipal principal,
        @PathVariable String kind,
        @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.saveImage(principal,
                kind,
                file));
    }

    @DeleteMapping("/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(
        @AuthenticationPrincipal OAuthPrincipal principal,
        @PathVariable String kind
    ) {
        service.deleteImage(principal, kind);
    }
}
