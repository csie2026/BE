package com.ggmount.weather;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 기상청 API 호출. KMA_SERVICE_KEY 환경변수가 있을 때만 실행됩니다. */
@SpringBootTest(properties = "app.kma.service-key=${KMA_SERVICE_KEY}")
@EnabledIfEnvironmentVariable(named = "KMA_SERVICE_KEY", matches = ".+")
class WeatherLiveTests {
    @Autowired
    WeatherService service;

    @Test
    void fetchesAndJudgesRealForecast() {
        long id = 3;
        // V2 시드의 산 하나
        WeatherResponse res = service.weather(id);
        assertFalse(res.days().isEmpty());
        res.days()
                .forEach(d -> System.out.println("WEATHER " + res.mountainName() + " " + d.date() + " " + d.level()
                    + " | " + d.message() + " | " + d.notes() + " | hours=" + d.hours().size()
                    + " first=" + d.hours().getFirst()));
        long t = System.nanoTime();
        service.weather(id);
        // 같은 발표분이면 캐시
        assertTrue(System.nanoTime() - t < 50_000_000L, "second call should hit the cache");
    }
}
