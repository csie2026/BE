package com.ggmount.weather;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 기상청 API 호출. KMA_SERVICE_KEY 환경변수가 있을 때만 실행됩니다. */
// 환경변수가 없거나 빈 값이면 클래스 실행을 건너뛴다. 값이 있어도 잘못된 키·외부 장애·네트워크 문제는 실패로 전파된다.
// Spring 테스트 컨텍스트와 시드 DB에 의존하며 Mock 응답 대신 서비스의 실제 HTTP 예보 조회를 사용한다. 관측값 검증은 아니다.
@SpringBootTest(properties = "app.kma.service-key=${KMA_SERVICE_KEY}")
@EnabledIfEnvironmentVariable(named = "KMA_SERVICE_KEY", matches = ".+")
class WeatherLiveTests {
    @Autowired
    WeatherService service;

    @Test
    // 산 ID 3의 예보 날짜가 비어 있지 않은지만 단언하고, 날짜별 등급·값의 정확성은 출력만 하며 단언하지 않는다.
    void fetchesAndJudgesRealForecast() {
        long id = 3;
        WeatherResponse res = service.weather(id);
        assertFalse(res.days().isEmpty());
        res.days()
                .forEach(d -> System.out.println("WEATHER " + res.mountainName() + " " + d.date() + " " + d.level()
                    + " | " + d.message() + " | " + d.notes() + " | hours=" + d.hours().size()
                    + " first=" + d.hours().getFirst()));
        long t = System.nanoTime();
        service.weather(id);
        // 두 번째 호출이 50ms 미만인지만 검사한다. HTTP 호출 횟수는 검증하지 않으며 발표분 변경·실행 환경의 영향이 있다.
        assertTrue(System.nanoTime() - t < 50_000_000L, "second call should hit the cache");
    }
}
