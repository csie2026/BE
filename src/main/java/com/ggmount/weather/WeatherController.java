package com.ggmount.weather;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// 산 ID를 해당 예보 격자로 연결하며 외부 예보 조회와 산행 적합도 판단은 서비스에 위임한다.
// 보안 설정에 따라 인증이 필요하며, 현재 위치 좌표 대신 저장된 산 ID로 조회한다. 예보 가공은 직접 수행하지 않는다.
@RestController
public class WeatherController {
    private final WeatherService service;

    public WeatherController(WeatherService service) {
        this.service = service;
    }

    // id는 long으로 변환하며 별도 양수 검증은 없다. 산이 없으면 404, 키 미설정은 503, 외부 조회·파싱 실패는 502다.
    // 정상 시 기본 200으로 일별 적합도와 시간별 예보를 반환하며, 캐시·응답 헤더를 여기서 별도로 지정하지 않는다.
    @GetMapping("/api/mountains/{id}/weather")
    public WeatherResponse weather(@PathVariable long id) {
        return service.weather(id);
    }
}
