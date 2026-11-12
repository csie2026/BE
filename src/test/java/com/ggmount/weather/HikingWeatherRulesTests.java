package com.ggmount.weather;

import com.ggmount.weather.HikingWeatherRules.Level;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 직접 만든 예보값으로 등급·문구와 예보 변환을 검증하며 외부 HTTP 호출이나 산행 인증·안전 보장은 검증하지 않는다.
class HikingWeatherRulesTests {
    static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 2, 9, 0);

    static HourForecast hour(int h, double tmp, int pty, int pop, double pcp, double sno, double wsd) {
        return new HourForecast(DAY.withHour(h), tmp, 1, pty, pop, pcp, sno, wsd, 50);
    }

    static HourForecast fine(int h) {
        return hour(h, 18, 0, 10, 0, 0, 2);
    }

    @Test
    void clearDayIsGood() {
        var v = HikingWeatherRules.judge(List.of(fine(9), fine(12), fine(15)));
        assertEquals(Level.GOOD, v.level());
        assertTrue(v.notes().isEmpty());
    }

    @Test
    // 기상 요소별 CAUTION·BAD 경계값을 검사한다.
    // 모든 기준의 직전·직후 값을 포괄하지는 않는다.
    void thresholds() {
        assertEquals(
            Level.CAUTION,
            HikingWeatherRules.judge(List.of(
                fine(9),
                hour(
                    12,
                    18,
                    0,
                    30,
                    0,
                    0,
                    2
                )
            )).level()
        );
        assertEquals(
            Level.BAD,
            HikingWeatherRules.judge(List.of(
                fine(9),
                hour(
                    12,
                    18,
                    0,
                    60,
                    0,
                    0,
                    2
                )
            )).level()
        );
        assertEquals(
            Level.CAUTION,
            HikingWeatherRules.judge(List.of(hour(
                12,
                18,
                1,
                20,
                0.5,
                0,
                2
            ))).level()
        );
        assertEquals(
            Level.BAD,
            HikingWeatherRules.judge(List.of(
                hour(
                    12,
                    18,
                    1,
                    20,
                    0.5,
                    0,
                    2
                ),
                hour(
                    13,
                    18,
                    1,
                    20,
                    0.5,
                    0,
                    2
                )
            )).level()
        );
        assertEquals(
            Level.BAD,
            HikingWeatherRules.judge(List.of(hour(12, 18, 0, 20, 3, 0, 2))).level()
        );
        assertEquals(
            Level.CAUTION,
            HikingWeatherRules.judge(List.of(hour(12, 18, 0, 20, 0, 0, 7))).level()
        );
        assertEquals(
            Level.BAD,
            HikingWeatherRules.judge(List.of(hour(12, 18, 0, 20, 0, 0, 10))).level()
        );
        assertEquals(
            Level.CAUTION,
            HikingWeatherRules.judge(List.of(hour(12, 30, 0, 20, 0, 0, 2))).level()
        );
        assertEquals(
            Level.BAD,
            HikingWeatherRules.judge(List.of(hour(12, 33, 0, 20, 0, 0, 2))).level()
        );
        assertEquals(
            Level.CAUTION,
            HikingWeatherRules.judge(List.of(hour(12, 0, 0, 20, 0, 0, 2))).level()
        );
        assertEquals(
            Level.BAD,
            HikingWeatherRules.judge(List.of(hour(12, -10, 0, 20, 0, 0, 2))).level()
        );
        assertEquals(
            Level.CAUTION,
            HikingWeatherRules.judge(List.of(hour(
                12,
                18,
                0,
                20,
                0,
                0.5,
                2
            ))).level()
        );
        assertEquals(
            Level.BAD,
            HikingWeatherRules.judge(List.of(hour(12, 18, 0, 20, 0, 1, 2))).level()
        );
    }

    @Test
    // 더 나쁜 등급의 대표 문구가 우선하고 나머지 조건은 notes로 남는지 확인한다.
    void worstFindingIsTheMessageAndTheRestAreNotes() {
        var v = HikingWeatherRules.judge(List.of(
            hour(9, 18, 0, 40, 0, 0, 11),
            hour(14, 31, 0, 20, 0, 0, 2)
        ));
        assertEquals(Level.BAD, v.level());
        assertTrue(v.message().contains("바람"), v.message());
        assertEquals(2, v.notes().size());
    }

    @Test
    // 수치 문자열 해석과 발표분 선택의 날짜·10분 경계를 검사한다.
    void parsesKmaAmountsAndBaseTimes() {
        assertEquals(0, KmaForecastClient.amount("강수없음"));
        assertEquals(0, KmaForecastClient.amount("적설없음"));
        assertEquals(0.5, KmaForecastClient.amount("1mm 미만"));
        assertEquals(0.5, KmaForecastClient.amount("1.0cm 미만"));
        assertEquals(2.4, KmaForecastClient.amount("2.4mm"));
        assertEquals(30, KmaForecastClient.amount("30.0~50.0mm"));
        assertEquals(50, KmaForecastClient.amount("50.0mm 이상"));

        assertEquals(
            LocalDateTime.of(
                2026,
                10,
                1,
                23,
                0
            ),
            KmaForecastClient.latestBase(LocalDateTime.of(
                2026,
                10,
                2,
                2,
                5
            ))
        );
        assertEquals(
            LocalDateTime.of(
                2026,
                10,
                2,
                2,
                0
            ),
            KmaForecastClient.latestBase(LocalDateTime.of(
                2026,
                10,
                2,
                2,
                10
            ))
        );
        assertEquals(
            LocalDateTime.of(
                2026,
                10,
                2,
                11,
                0
            ),
            KmaForecastClient.latestBase(LocalDateTime.of(
                2026,
                10,
                2,
                13,
                59
            ))
        );
        assertEquals(
            LocalDateTime.of(
                2026,
                10,
                2,
                23,
                0
            ),
            KmaForecastClient.latestBase(LocalDateTime.of(
                2026,
                10,
                2,
                23,
                30
            ))
        );
    }

    @Test
    // 항목 병합과 산행 시간대 선택을 검사하며 누락 기상값의 등급 영향은 단언하지 않는다.
    void groupsKmaItemsByHourAndKeepsOnlyHikingHours() {
        var items = List.of(
            new KmaForecastClient.KmaResponse.Item("TMP", "20261002", "0500", "9"),
            new KmaForecastClient.KmaResponse.Item("TMP", "20261002", "0900", "14"),
            new KmaForecastClient.KmaResponse.Item("PCP", "20261002", "0900", "1mm 미만"),
            new KmaForecastClient.KmaResponse.Item("TMP", "20261003", "1200", "20")
        );
        List<HourForecast> hours = KmaForecastClient.toHours(items);
        assertEquals(3, hours.size());
        assertEquals(0.5, hours.get(1).precipitationMm());

        var res = WeatherService.build(
            new WeatherService.Mountain(
                1,
                "산",
                60,
                127
            ),
            DAY,
            hours,
            DAY.withHour(4)
        );
        assertEquals(2, res.days().size());
        assertEquals(1, res.days().getFirst().hours().size());
        // 05시는 06~18시 밖
    }
}
