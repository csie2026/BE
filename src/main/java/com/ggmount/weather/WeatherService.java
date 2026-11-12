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

/** 산의 저장 격자로 단기예보를 조회·재사용하고 일별 산행 적합도 응답을 구성한다. 실시간 관측값을 제공하는 서비스는 아니다. */
@Service
public class WeatherService {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final int HIKE_FROM = 6, HIKE_TO = 18;
    // 등산 판단 시간대 06~18시

    private final JdbcTemplate jdbc;
    private final KmaForecastClient kma;
    // 이 서비스 인스턴스의 메모리에 격자별 마지막 조회 발표분 하나를 보관하며 서버 간 공유 캐시는 아니다.
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(LocalDateTime base, List<HourForecast> hours) {
    }

    record Mountain(long id, String name, int nx, int ny) {
    }

    public WeatherService(JdbcTemplate jdbc, KmaForecastClient kma) {
        this.jdbc = jdbc;
        this.kma = kma;
    }

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
        // 서울 현재 시각에서 발표 후 10분의 여유를 적용해 02·05·08·11·14·17·20·23시 중 최신 발표분을 클라이언트가 선택한다.
        LocalDateTime base = KmaForecastClient.latestBase(now);
        String key = m.nx() + "," + m.ny();
        Cached c = cache.get(key);
        // 발표분이 같으면 재사용하고 다르면 클라이언트가 외부 호출·시간별 항목 병합을 수행한다.
        // 동시 요청을 하나로 합치지는 않으며, 실패 시 이전 캐시를 대신 반환하거나 이 서비스에서 재시도하지 않는다.
        if (c == null || !c.base().equals(base)) {
            c = new Cached(base, kma.forecast(m.nx(), m.ny(), base));
            cache.put(key, c);
        }
        return build(m, c.base(), c.hours(), now);
    }

    // 현재 정시 이전만 제외하므로 현재 시간대는 포함하며, 날짜별 산행 시간대의 적합도를 판단한다.
    static WeatherResponse build(
        Mountain m,
        LocalDateTime base,
        List<HourForecast> hours,
        LocalDateTime now
    ) {
        // 날짜 오름차순으로 묶으며 누락된 시간대를 보충하지 않는다.
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
            // 06~18시 양 끝을 포함한 예보를 규칙 클래스에 전달해 등급·메시지·주의 사항을 구성한다.
            HikingWeatherRules.Verdict v = HikingWeatherRules.judge(window);
            days.add(new WeatherResponse.Day(date, v.level(), v.message(), v.notes(), window));
            }
        );
        // 선택된 날짜가 없어도 별도 오류 없이 빈 days를 반환하며 발표 시각과 예보 출처를 함께 제공한다.
        return new WeatherResponse(m.id(), m.name(), base, days, "기상청 단기예보");
    }
}
