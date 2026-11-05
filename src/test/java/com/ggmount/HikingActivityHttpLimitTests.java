package com.ggmount;

import com.ggmount.hiking.controller.HikingActivityBodyLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HikingActivityHttpLimitTests {
    @LocalServerPort int port;

    @Test void actualChunkedHttpBodyIsRejectedWithoutContentLength() throws Exception {
        byte[] bytes = new byte[HikingActivityBodyLimitFilter.MAX_BYTES + 1];
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
        assertEquals(-1, publisher.contentLength());
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/api/users/me/hiking-records", "/api/users/me/%68iking-records"}) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json").POST(publisher).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(413, response.statusCode());
                assertTrue(response.body().contains("hiking_request_too_large"));
            }
        }
    }
}
