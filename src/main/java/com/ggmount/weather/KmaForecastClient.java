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
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 기상청 단기예보 조회서비스(getVilageFcst)의 첫 페이지 최대 1000개 항목을 시간별 예보로 변환한다. */
@Component
public class KmaForecastClient {
    private static final String URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    // 이 클라이언트에서 연결·응답 시간 초과를 별도 설정하거나 재시도·캐시를 구현하지는 않는다.
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

    /** 현재 시각에서 10분을 뺀 기준으로 최신 발표분을 선택한다. 외부 API의 자료 제공 완료를 보장하지는 않는다. */
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

    // 서비스가 선택한 발표 날짜·시각과 산의 격자 좌표를 사용하며, 인증키가 비어 있으면 호출 전 503으로 거부한다.
    public List<HourForecast> forecast(int nx, int ny, LocalDateTime base) {
        if (serviceKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "날씨 서비스 키가 설정되지 않았습니다.");
        }
        // 키는 디코딩된 인증키이며 템플릿 변수로 넣어 '+', '/', '='도 인코딩한다.
        // 발표 날짜·시각은 uuuuMMdd·HHmm 형식으로 요청한다.
        URI uri = UriComponentsBuilder.fromUriString(URL)
            .queryParam("serviceKey", "{key}")
            .queryParam("pageNo", 1)
            .queryParam("numOfRows", 1000)
            .queryParam("dataType", "JSON").queryParam("base_date", base.format(DATE))
            .queryParam("base_time", base.format(TIME))
            .queryParam("nx", nx)
            .queryParam("ny", ny)
            .encode().buildAndExpand(serviceKey).toUri();
        try {
            return parseResponse(http.get().uri(uri).retrieve().body(KmaResponse.class));
        }
        catch (Exception e) {
            // 키 오류 등은 JSON 대신 XML이 와서 파싱에서 실패합니다
            // HTTP 호출·응답 변환 실패는 고정된 502 메시지로 전파하며 원본 예외·요청 URI·인증키를 메시지에 넣지 않는다.
            // 이 클래스는 직접 로그를 남기지 않지만 하위 HTTP 계층의 로그 마스킹을 설정하는 코드는 없다.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "날씨 정보를 가져오지 못했습니다.");
        }
    }

    // 성공 코드 00과 필수 응답 구조를 확인한다. 누락·실패 코드·항목 변환 오류는 502이며 빈 항목 목록은 허용한다.
    static List<HourForecast> parseResponse(KmaResponse body) {
        if (body == null
            || body.response() == null
            || body.response().header() == null
            || !"00".equals(body.response()
                .header()
                .resultCode())
            || body.response().body() == null
            || body.response().body().items() == null
            || body.response().body().items().item() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "날씨 정보를 가져오지 못했습니다.");
        }
        try {
            return toHours(body.response().body().items().item());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "날씨 정보를 가져오지 못했습니다.");
        }
    }

    // 기상청은 기온·강수 등을 항목별로 반환하므로 같은 예보 시각의 항목을 하나의 시간별 예보로 합친다.
    // 예보 대상 날짜·시각을 엄격히 파싱해 시간순으로 묶으며 같은 시각·항목 코드가 반복되면 마지막 값을 사용한다.
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

    // 일반 수치의 누락·숫자 변환 실패는 null로 처리하며 유한성이나 기상값 범위를 별도로 검사하지 않는다.
    private static Double dbl(String s) {
        try {
            return s == null ? null : Double.valueOf(s);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    // 정수 항목도 실수로 읽은 뒤 정수로 변환하므로 소수 부분은 반올림하지 않고 버린다.
    private static Integer integer(String s) {
        Double d = dbl(s);
        return d == null ? null : d.intValue();
    }
}
