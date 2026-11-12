package com.ggmount;

import com.ggmount.hiking.controller.HikingActivityBodyLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

// 모의 Servlet 요청으로 필터를 직접 호출한다. 길이 미지정 본문도 검사하지만 실제 HTTP 전송은 별도 테스트에서 다룬다.
class HikingActivityBodyLimitTests {
    @Test void requestDiagnosticsDoNotExposeEvidence() {
        // 문자열 변환의 고정 문구만 검사하며 로그 수집이나 JSON 직렬화 전체의 비노출을 검증하지는 않는다.
        var point = new com.ggmount.hiking.dto.GpsPointRequest(37.123456, 127.654321, java.time.Instant.EPOCH, 5.0);
        var request = new com.ggmount.hiking.dto.HikingActivityRequest(1L, null, java.time.Instant.EPOCH,
            java.time.Instant.EPOCH, 0.0, 0L, true, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            new com.ggmount.hiking.dto.GpsEvidence(37.123456,127.654321,5.0,java.time.Instant.EPOCH),
            java.util.List.of(java.util.List.of(point)));
        assertEquals("GpsPointRequest[redacted]", point.toString());
        assertEquals("GpsEvidence[redacted]", request.gpsEvidence().toString());
        assertEquals("HikingActivityRequest[redacted]", request.toString());
    }

    @Test void unknownLengthStreamsHaveTheSameLimitAsDeclaredBodies() throws Exception {
        var filter = new HikingActivityBodyLimitFilter();
        for (int size : new int[]{HikingActivityBodyLimitFilter.MAX_BYTES, HikingActivityBodyLimitFilter.MAX_BYTES + 1}) {
            var request = new MockHttpServletRequest("POST", "/api/users/me/hiking-records") {
                @Override public long getContentLengthLong() { return -1; }
                @Override public int getContentLength() { return -1; }
            };
            request.addHeader("Transfer-Encoding", "chunked");
            request.setContent(new byte[size]);
            var response = new MockHttpServletResponse();
            var read = new AtomicInteger();
            filter.doFilter(request, response, (req, res) -> read.set(req.getInputStream().readAllBytes().length));
            assertEquals(size > HikingActivityBodyLimitFilter.MAX_BYTES ? 413 : 200, response.getStatus());
            assertEquals(size > HikingActivityBodyLimitFilter.MAX_BYTES ? 0 : size, read.get());
        }
    }

    @Test void otherApiBodiesAreNotLimitedByHikingFilter() throws Exception {
        var filter = new HikingActivityBodyLimitFilter();
        var request = new MockHttpServletRequest("PUT", "/api/users/me/images/profile");
        request.setContent(new byte[HikingActivityBodyLimitFilter.MAX_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var called = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> { assertSame(request, req); called.incrementAndGet(); });
        assertEquals(1, called.get());
        assertEquals(200, response.getStatus());
    }
}
