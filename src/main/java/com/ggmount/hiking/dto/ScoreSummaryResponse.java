package com.ggmount.hiking.dto;
/**
 * 회원 총점은 방문·정상·공개 일지 보너스 합계다. 합산 대상이 없는 항목은 0이며 policyVersion은 적용한 정책 버전이다.
 * 공개 보너스는 본인 활동에 연결된 공개 일지가 있는 산별 50점으로, 활동 인증 여부와 무관하며 연결 없는 일지는 제외한다.
 */
public record ScoreSummaryResponse(long totalPoints, long visitPoints, long summitPoints,
    long publicBonusPoints, String policyVersion) {}
