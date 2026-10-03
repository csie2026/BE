package com.ggmount.weather;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class WeatherService {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final int HIKE_FROM = 6, HIKE_TO = 18;
    // 등산 판단 시간대 06~18시

    private final JdbcTemplate jdbc;
    private final KmaForecastClient kma;
    // ponytail: 격자별 최신 발표분 하나만 메모리에 보관, 서버 여러 대면 Redis 등 공용 캐시로
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(LocalDateTime base, List<HourForecast> hours) {
    }

    record Mountain(long id, String name, int nx, int ny) {
    }

    public WeatherService(JdbcTemplate jdbc, KmaForecastClient kma) {
        this.jdbc = jdbc;
        this.kma = kma;
    }

    // 같은 예보 격자의 산은 최신 발표분을 공유해 외부 API 중복 호출을 줄인다.
    // 발표 시각이 바뀌면 같은 격자라도 새 예보를 조회한다.
    public WeatherResponse weather(long mountainId) {
        Mountain m = jdbc.query(
            "select id, name, grid_nx, grid_ny from mountains where id = ?",
            (rs,
                i) -> new Mountain(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getInt(4)
                ),
            mountainId
        )
        .stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "산을 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDateTime base = KmaForecastClient.latestBase(now);
        String key = m.nx() + "," + m.ny();
        Cached c = cache.get(key);
        if (c == null || !c.base().equals(base)) {
            c = new Cached(base, kma.forecast(m.nx(), m.ny(), base));
            cache.put(key, c);
        }
        return build(m, c.base(), c.hours(), now);
    }

    // 이미 지난 예보를 제외하고 서울 시간의 산행 시간대만 일별로 묶어 적합도 규칙에 전달한다.
    static WeatherResponse build(
        Mountain m,
        LocalDateTime base,
        List<HourForecast> hours,
        LocalDateTime now
    ) {
        Map<LocalDate, List<HourForecast>> byDay = hours.stream()
        .filter(h -> !h.time().isBefore(now.withMinute(0).withSecond(0).withNano(0)))
        .collect(Collectors.groupingBy(
            h -> h.time().toLocalDate(),
            java.util.TreeMap::new,
            Collectors.toList()
        ));
        List<WeatherResponse.Day> days = new ArrayList<>();
        byDay.forEach((date, list) -> {
            List<HourForecast> window = list.stream()
            .filter(h -> h.time().getHour() >= HIKE_FROM && h.time().getHour() <= HIKE_TO).toList();
            if (window.isEmpty()) {
            return; // 오늘 산행 시간대가 이미 지났거나 예보가 없는 날
            }
            HikingWeatherRules.Verdict v = HikingWeatherRules.judge(window);
            days.add(new WeatherResponse.Day(date, v.level(), v.message(), v.notes(), window));
            }
        );
        return new WeatherResponse(m.id(), m.name(), base, days, "기상청 단기예보");
    }
}
