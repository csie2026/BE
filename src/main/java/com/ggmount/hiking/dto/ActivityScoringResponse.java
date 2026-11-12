package com.ggmount.hiking.dto;
import java.time.LocalDate;
/**
 * 개별 활동의 저장 점수로 회원 총점·공개 일지 보너스와 구분한다. 결과 행이 없으면 scoring은 null이다.
 * 신규 status는 CERTIFIED·GPS_REQUIRED·BELOW_MINIMUM이며 DAILY_LIMIT는 구정책 기록 조회에만 남긴다.
 * certificationDate는 미인증에도 기록되는 시작 시각의 서울 날짜이고 policyVersion은 저장 결과의 정책 버전이다.
 */
public record ActivityScoringResponse(String status, LocalDate certificationDate,
    int visitPoints, int summitPoints, String policyVersion) {}
