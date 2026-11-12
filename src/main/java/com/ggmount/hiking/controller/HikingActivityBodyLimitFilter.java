package com.ggmount.hiking.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 길이가 없거나 청크로 전송된 요청도 실제 읽은 크기를 제한한 뒤 요청 범위에서 재사용한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class HikingActivityBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getRequestURI()와 달리 서블릿 경로는 컨테이너에서 디코딩된 값이다.
        String path = request.getServletPath();
        if (path == null || path.isEmpty())
            path = org.springframework.web.util.UriUtils.decode(
                request.getRequestURI().substring(request.getContextPath().length()), StandardCharsets.UTF_8);
        return !"POST".equals(request.getMethod()) || !"/api/users/me/hiking-records".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            tooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes, int off, int length) { return input.read(bytes, off, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        try {
                            if (!isFinished()) listener.onDataAvailable();
                            if (isFinished()) listener.onAllDataRead();
                        } catch (IOException e) { listener.onError(e); }
                    }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"hiking_request_too_large\",\"message\":\"산행 요청은 2MiB 이하여야 합니다.\"}");
    }
}
