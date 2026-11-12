package com.ggmount;

import com.ggmount.hiking.controller.HikingActivityBodyLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

// 임의 포트 로컬 서버에 길이 미지정 본문을 실제 전송해 일반·퍼센트 인코딩 경로의 제한을 확인한다.
// 인증·CSRF 없는 요청이므로 용량 필터의 선행 차단을 검사하며 산행 저장까지 진행하지 않는다.
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
