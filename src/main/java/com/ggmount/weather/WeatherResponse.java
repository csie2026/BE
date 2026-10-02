package com.ggmount.weather;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 날짜별 등산 적합도와 06~18시 시간별 예보. source는 출처 표시용(공공누리 1유형). */
public record WeatherResponse(long mountainId, String mountainName, LocalDateTime baseTime, List<Day> days, String source) {

    public record Day(LocalDate date, HikingWeatherRules.Level level, String message, List<String> notes,
                      List<HourForecast> hours) {
    }
}
