package com.ggmount.weather;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

// 응답 객체를 직접 구성해 파서를 검증한다. HTTP Mock 서버·실제 외부 호출·시간 초과·재시도·요청 인코딩은 검증하지 않는다.
class KmaForecastClientTests {
    private KmaForecastClient.KmaResponse response(List<KmaForecastClient.KmaResponse.Item> items) {
        return new KmaForecastClient.KmaResponse(new KmaForecastClient.KmaResponse.Response(
            new KmaForecastClient.KmaResponse.Header("00", "NORMAL_SERVICE"),
            new KmaForecastClient.KmaResponse.Body(new KmaForecastClient.KmaResponse.Items(items))));
    }

    private void badGateway(KmaForecastClient.KmaResponse body) {
        var error = assertThrows(ResponseStatusException.class, () -> KmaForecastClient.parseResponse(body));
        assertEquals(502, error.getStatusCode().value());
    }

    // 본문·중첩 필드 누락을 502로 처리하는지 확인한다. 오류 코드 30 사례에는 본문 누락도 함께 존재한다.
    @Test void missingNestedFieldsBecome502() {
        badGateway(null);
        badGateway(new KmaForecastClient.KmaResponse(null));
        badGateway(new KmaForecastClient.KmaResponse(new KmaForecastClient.KmaResponse.Response(null, null)));
        var ok = new KmaForecastClient.KmaResponse.Header("00", "OK");
        badGateway(new KmaForecastClient.KmaResponse(new KmaForecastClient.KmaResponse.Response(ok, null)));
        badGateway(new KmaForecastClient.KmaResponse(new KmaForecastClient.KmaResponse.Response(ok, new KmaForecastClient.KmaResponse.Body(null))));
        badGateway(response(null));
        badGateway(new KmaForecastClient.KmaResponse(new KmaForecastClient.KmaResponse.Response(new KmaForecastClient.KmaResponse.Header("30", "ERROR"), null)));
    }

    // 빈 데이터와 선택 수치의 누락·변환 실패를 구분한다.
    @Test void emptyArrayIsNoDataAndMissingValuesRemainNullable() {
        assertTrue(KmaForecastClient.parseResponse(response(List.of())).isEmpty());
        var hours = KmaForecastClient.parseResponse(response(List.of(
            new KmaForecastClient.KmaResponse.Item("TMP", "20261107", "0900", "12"),
            new KmaForecastClient.KmaResponse.Item("SKY", "20261107", "0900", "invalid"))));
        assertEquals(1, hours.size());
        assertEquals(12.0, hours.getFirst().temperature());
        assertNull(hours.getFirst().sky());
        assertNull(hours.getFirst().pop());
    }

    // 잘못된 날짜·시각과 null 항목의 파싱 실패를 검사한다.
    @Test void invalidDatesTimesAndNullItemsBecome502() {
        for (String date : List.of("bad", "20260230", "20261301"))
            badGateway(response(List.of(new KmaForecastClient.KmaResponse.Item("TMP", date, "0900", "12"))));
        for (String time : List.of("bad", "2400", "0960"))
            badGateway(response(List.of(new KmaForecastClient.KmaResponse.Item("TMP", "20261107", time, "12"))));
        badGateway(response(Arrays.asList((KmaForecastClient.KmaResponse.Item) null)));
    }

    // 빈 인증키는 외부 요청 전에 503으로 거부하는지 확인하며 실제 HTTP 오류 응답을 만드는 테스트는 아니다.
    @Test void missingKeyRemains503() {
        var error = assertThrows(ResponseStatusException.class, () ->
            new KmaForecastClient("").forecast(60, 127, LocalDateTime.of(2026, 11, 7, 8, 0)));
        assertEquals(503, error.getStatusCode().value());
    }
}
