package com.ggmount.weather;

import java.time.LocalDateTime;

/**
 * 기상청 단기예보 한 시간치. 강수량·적설은 "1mm 미만" 같은 문자열을 숫자로 바꾼 값입니다.
 * 실시간 관측값이 아니며, 모델 자체는 검증·계산하지 않는다. 서비스가 시간대를 선택해 등산 적합도 규칙에 전달한다.
 * 일반 수치 항목의 누락·변환 실패는 null이며, 강수량·적설은 누락·없음·숫자 없는 문자열을 0으로 처리한다.
 *
 * @param time fcstDate·fcstTime을 합친 예보 대상 시각으로, 서비스의 서울 시각 기준으로 사용하며 발표 시각과 구분한다
 * @param temperature TMP 1시간 기온(섭씨도)
 * @param sky 하늘상태 1 맑음, 3 구름많음, 4 흐림
 * @param pty 강수형태 0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기
 * @param pop POP 강수확률(%)
 * @param precipitationMm PCP 1시간 강수량(mm), 범위 문자열은 첫 숫자·미만 문자열은 첫 숫자의 절반으로 변환한 값
 * @param snowCm SNO 1시간 신적설(cm), 강수량과 동일한 문자열 수치화 함수를 적용한 값
 * @param windSpeed WSD 풍속(m/s)
 * @param humidity REH 습도(%)
 */
public record HourForecast(
    LocalDateTime time,
    Double temperature,
    Integer sky,
    Integer pty,
    Integer pop,
    double precipitationMm,
    double snowCm,
    Double windSpeed,
    Integer humidity
) {
}
