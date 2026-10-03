package com.ggmount.weather;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// 산 ID를 해당 예보 격자로 연결하며 외부 예보 조회와 산행 적합도 판단은 서비스에 위임한다.
@RestController
public class WeatherController {
    private final WeatherService service;

    public WeatherController(WeatherService service) {
        this.service = service;
    }

    @GetMapping("/api/mountains/{id}/weather")
    public WeatherResponse weather(@PathVariable long id) {
        return service.weather(id);
    }
}
