package com.ggmount.member.service;

import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.member.dto.MemberResponse;
import com.ggmount.member.repository.PersonalDataRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class PersonalDataService {
    private final MemberService members;
    private final PersonalDataRepository data;
    private final ImageUploadValidator images;

    public PersonalDataService(
        MemberService members,
        PersonalDataRepository data,
        ImageUploadValidator images
    ) {
        this.members = members;
        this.data = data;
        this.images = images;
    }

    // 현재 회원과 이미지 종류·선택적 revision을 함께 확인해 다른 계정 또는 오래된 URL의 접근을 제한한다.
    public ImageUploadValidator.ImageData image(OAuthPrincipal principal, String kind, String revision) {
        Long memberId = members.current(principal).getId();
        return data.findImage(memberId, imageKind(kind), revision).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지가 없거나 현재 계정의 이미지 URL이 아닙니다."));
    }

    @Transactional
    // 검증된 이미지 저장과 revision 갱신을 회원 잠금 아래 수행해 동시 업로드의 충돌을 막는다.
    public MemberResponse saveImage(OAuthPrincipal principal, String kind, MultipartFile file) {
        Long memberId = members.current(principal).getId();
        String selectedKind = imageKind(kind);
        var image = images.validate(file);
        data.lockMember(memberId);
        data.saveImage(memberId, selectedKind, image);
        return members.me(principal);
    }

    @Transactional
    public void deleteImage(OAuthPrincipal principal, String kind) {
        Long memberId = members.current(principal).getId();
        String selectedKind = imageKind(kind);
        data.lockMember(memberId);
        data.deleteImage(memberId, selectedKind);
    }

    private String imageKind(String kind) {
        if (!"profile".equals(kind) && !"background".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "지원하지 않는 이미지 종류입니다.");
        }
        return kind;
    }
}
