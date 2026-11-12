package com.ggmount.hiking.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 서비스의 산행 저장 충돌이나 컨트롤러가 변환한 지정 제약 위반을 표현하는 409 예외다.
 * 공통 예외 처리기가 충돌 코드와 메시지를 응답에 포함한다. 내용이 일치하는 재전송은 서비스에서 기존 산행을 반환한다.
 */
public class HikingActivityConflict extends ResponseStatusException {
    private final String code;

    private HikingActivityConflict(String code, String message) {
        super(HttpStatus.CONFLICT, message);
        this.code = code;
    }

    public String code() { return code; }

    // 같은 회원·요청 ID의 후보가 여러 개이거나, 기존 기록의 버전에 따라 필요한 경로가 없거나 지문·필드 대조가 불일치하면 사용한다.
    // 컨트롤러에서 지정된 요청 ID 유일성 제약 위반을 변환할 때도 사용한다.
    public static HikingActivityConflict request() {
        return new HikingActivityConflict("client_request_conflict", "저장 요청 ID의 기존 내용과 다릅니다.");
    }

    // 같은 회원·산·시작·종료 시각의 산행이 있거나, 지정된 산행 세션 유일성 제약 위반을 변환할 때 사용한다.
    public static HikingActivityConflict duplicate() {
        return new HikingActivityConflict("duplicate_hiking_activity", "이미 저장된 산행입니다.");
    }
}
