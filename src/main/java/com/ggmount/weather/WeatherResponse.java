package com.ggmount.weather;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 날짜별 등산 적합도와 06~18시 시간별 예보. source는 출처 표시용(공공누리 1유형). */
// baseTime은 발표 시각이며 예보 대상 시각은 days의 hours에 있다.
// 서비스는 현재 정시 이후 06~18시 자료가 없는 날을 제외한다. 대상 날짜가 없으면 days는 null이 아닌 빈 목록이다.
public record WeatherResponse(
    long mountainId,
    String mountainName,
    LocalDateTime baseTime,
    List<Day> days,
    String source
) {

    /**
     * level·message는 내부 규칙의 대표 판정, notes는 나머지 위험 안내다.
     * hours의 누락 수치는 null 또는 강수량·적설의 0으로 전달될 수 있다.
     */
    public record Day(
        LocalDate date,
        HikingWeatherRules.Level level,
        String message,
        List<String> notes,
        List<HourForecast> hours
    ) {
    }
}
