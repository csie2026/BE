package com.ggmount.weather;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 기상청 단기예보 조회서비스(getVilageFcst) 호출. 3시간마다 발표되는 3일치 시간별 예보를 받습니다. */
@Component
public class KmaForecastClient {
    private static final String URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    private final RestClient http = RestClient.create();
    private final String serviceKey;

    public KmaForecastClient(@Value("${app.kma.service-key:}") String serviceKey) {
        this.serviceKey = serviceKey;
    }

    record KmaResponse(Response response) {
        record Response(Header header, Body body) {
        }
        record Header(String resultCode, String resultMsg) {
        }
        record Body(Items items) {
        }
        record Items(List<Item> item) {
        }
        record Item(String category, String fcstDate, String fcstTime, String fcstValue) {
        }
    }

    /** 발표시각(02,05,...,23시)은 발표 10분 뒤부터 조회됩니다. */
    public static LocalDateTime latestBase(LocalDateTime now) {
        LocalDateTime t = now.minusMinutes(10);
        int hour = t.getHour();
        int base = hour < 2 ? -1 : ((hour - 2) / 3) * 3 + 2;
        return base < 0
            ? t.toLocalDate()
            .minusDays(1)
            .atTime(23, 0)
            : t.toLocalDate()
                .atTime(base, 0);
    }

    public List<HourForecast> forecast(int nx, int ny, LocalDateTime base) {
        if (serviceKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "날씨 서비스 키가 설정되지 않았습니다.");
        }
        // 키는 Decoding 키. 템플릿 변수로 넣어야 '+', '/', '='까지 인코딩됩니다.
        URI uri = UriComponentsBuilder.fromUriString(URL)
            .queryParam("serviceKey", "{key}")
            .queryParam("pageNo", 1)
            .queryParam("numOfRows", 1000)
            .queryParam("dataType", "JSON").queryParam("base_date", base.format(DATE))
            .queryParam("base_time", base.format(TIME))
            .queryParam("nx", nx)
            .queryParam("ny", ny)
            .encode().buildAndExpand(serviceKey).toUri();
        KmaResponse body;
        try {
            body = http.get().uri(uri).retrieve().body(KmaResponse.class);
        }
        catch (Exception e) {
            // 키 오류 등은 JSON 대신 XML이 와서 파싱에서 실패합니다
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "날씨 정보를 가져오지 못했습니다.", e);
        }
        if (body == null
            || body.response() == null
            || !"00".equals(body.response()
                .header()
                .resultCode())
            || body.response().body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "날씨 정보를 가져오지 못했습니다.");
        }
        return toHours(body.response().body().items().item());
    }

    // 기상청은 기온·강수 등을 항목별로 반환하므로 같은 예보 시각의 항목을 하나의 시간별 예보로 합친다.
    static List<HourForecast> toHours(List<KmaResponse.Item> items) {
        Map<LocalDateTime, Map<String, String>> byTime = new TreeMap<>();
        for (KmaResponse.Item it : items) {
            LocalDateTime t = LocalDate.parse(it.fcstDate(),
                DATE).atTime(LocalTime.parse(it.fcstTime(),
                    TIME));
            byTime.computeIfAbsent(t, k -> new java.util.HashMap<>())
                .put(it.category(), it.fcstValue());
        }
        return byTime.entrySet().stream().map(e -> {
            Map<String, String> v = e.getValue();
            return new HourForecast(
                e.getKey(),
                dbl(v.get("TMP")),
                integer(v.get("SKY")),
                integer(v.get("PTY")),
                integer(v.get("POP")),
                amount(v.get("PCP")),
                amount(v.get("SNO")),
                dbl(v.get("WSD")),
                integer(v.get("REH"))
            );
            }
        ).toList();
    }

    /** "강수없음"/"적설없음" → 0, "1mm 미만" → 0.5, "30.0~50.0mm" → 30, "50.0mm 이상" → 50. */
    static double amount(String s) {
        if (s == null || s.endsWith("없음")) {
            return 0;
        }
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return 0;
        }
        double v = Double.parseDouble(m.group());
        return s.contains("미만") ? v / 2 : v;
    }

    private static Double dbl(String s) {
        try {
            return s == null ? null : Double.valueOf(s);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(String s) {
        Double d = dbl(s);
        return d == null ? null : d.intValue();
    }
}
