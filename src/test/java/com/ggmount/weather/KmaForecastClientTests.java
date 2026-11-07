package com.ggmount.weather;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

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

    @Test void invalidDatesTimesAndNullItemsBecome502() {
        for (String date : List.of("bad", "20260230", "20261301"))
            badGateway(response(List.of(new KmaForecastClient.KmaResponse.Item("TMP", date, "0900", "12"))));
        for (String time : List.of("bad", "2400", "0960"))
            badGateway(response(List.of(new KmaForecastClient.KmaResponse.Item("TMP", "20261107", time, "12"))));
        badGateway(response(Arrays.asList((KmaForecastClient.KmaResponse.Item) null)));
    }

    @Test void missingKeyRemains503() {
        var error = assertThrows(ResponseStatusException.class, () ->
            new KmaForecastClient("").forecast(60, 127, LocalDateTime.of(2026, 11, 7, 8, 0)));
        assertEquals(503, error.getStatusCode().value());
    }
}
