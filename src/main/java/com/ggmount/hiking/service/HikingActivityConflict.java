package com.ggmount.hiking.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class HikingActivityConflict extends ResponseStatusException {
    private final String code;

    private HikingActivityConflict(String code, String message) {
        super(HttpStatus.CONFLICT, message);
        this.code = code;
    }

    public String code() { return code; }

    public static HikingActivityConflict request() {
        return new HikingActivityConflict("client_request_conflict", "저장 요청 ID의 기존 내용과 다릅니다.");
    }

    public static HikingActivityConflict duplicate() {
        return new HikingActivityConflict("duplicate_hiking_activity", "이미 저장된 산행입니다.");
    }
}
