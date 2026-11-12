package com.ggmount.member.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 편집 화면의 예상 회원과 실제 인증 회원이 다른 경우 쓰기 전에 거부한다. */
public class ProfileAccountMismatch extends ResponseStatusException {
    public ProfileAccountMismatch() {
        super(HttpStatus.CONFLICT, "로그인 계정이 변경되었습니다. 현재 계정을 다시 확인해주세요.");
    }
}
