package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.HikingActivityRequest;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 같은 회원·요청 ID로 재전송된 산행의 내용 대조에 사용하는 지문을 생성한다.
 * 중복 조회·저장 제어는 서비스가 담당하며, 지문은 사용자 인증이나 GPS 진위 증명을 대신하지 않는다.
 */
public final class HikingRequestFingerprint {
    public static final short VERSION = 1;
    private HikingRequestFingerprint() {}

    /**
     * 버전 1의 자료형별 이진 필드와 목록 길이를 SHA-256으로 해시해 16진수 문자열로 반환한다.
     * JSON 문자열 표현은 사용하지 않으며, 산·선택 코스·산행 시각·거리·경과 시간·제출 도달 여부와 전체 경로를 포함한다.
     * 회원 ID·요청 UUID·선택적 단일 GPS 표본은 이 지문 입력에 포함하지 않는다.
     * 서비스는 저장용으로 정규화한 요청을 전달하며, 이 메서드에서는 좌표나 표본 시각을 추가로 반올림하지 않는다.
     */
    public static String of(HikingActivityRequest request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                // 버전을 해시 입력에도 넣으며, 서비스는 저장된 지문 버전과 값이 모두 일치하는지 확인한다.
                out.writeShort(VERSION);
                out.writeLong(request.mountainId());
                out.writeBoolean(request.courseId() != null);
                if (request.courseId() != null) out.writeLong(request.courseId());
                instant(out, request.startedAt());
                instant(out, request.endedAt());
                out.writeDouble(request.distanceMeters());
                out.writeLong(request.elapsedMs());
                out.writeBoolean(request.completed()); // 서버 판정 결과가 아닌 클라이언트 제출 값이다.
                out.writeInt(request.gpsSegments().size());
                for (var segment : request.gpsSegments()) {
                    out.writeInt(segment.size());
                    for (var point : segment) {
                        out.writeDouble(point.lat());
                        out.writeDouble(point.lng());
                        instant(out, point.measuredAt());
                        out.writeDouble(point.accuracyMeters());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException impossible) {
            // 해시 계산 기반 기능의 실패는 요청 검증 실패로 바꾸지 않고 내부 상태 예외로 전파한다.
            throw new IllegalStateException("Unable to calculate request fingerprint", impossible);
        }
    }

    // 시각은 유닉스 기준 초와 나노초 부분으로 나누어 기록해 전달받은 정밀도를 유지한다.
    private static void instant(DataOutputStream out, Instant instant) throws IOException {
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
    }
}
