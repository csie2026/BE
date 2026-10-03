package com.ggmount.weather;

import java.time.LocalDateTime;

/**
 * 기상청 단기예보 한 시간치. 강수량·적설은 "1mm 미만" 같은 문자열을 숫자로 바꾼 값입니다.
 *
 * @param sky 하늘상태 1 맑음, 3 구름많음, 4 흐림
 * @param pty 강수형태 0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기
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
