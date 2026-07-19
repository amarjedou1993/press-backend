package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for the PUBLIC auth endpoints (/api/auth/**):
 * a fixed one-minute window per client IP. Exceeding the limit answers
 * 429 with Retry-After — the request never reaches the login machinery.
 *
 * Design notes:
 * - Registered as a plain servlet filter (@Component + @Order), running
 *   BEFORE the Spring Security chain — deliberately NOT added to the
 *   security chain, so it executes exactly once.
 * - In-memory by design: single-instance V1. If the app ever scales to
 *   multiple instances, this becomes a Redis bucket — same interface.
 * - The limit is configuration (app.security.auth-requests-per-minute):
 *   10 in prod-like defaults, generous in dev, tiny in the dedicated test.
 * - IP resolution trusts X-Forwarded-For's first entry when present
 *   (we sit behind nginx in prod); direct connections use remoteAddr.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000;

    private record Window(long startMillis, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;

    public AuthRateLimitFilter(AppProperties props) {
        this.limit = props.security().authRequestsPerMinute();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = clientIp(request);
        long now = System.currentTimeMillis();

        Window w = windows.compute(ip, (key, current) ->
                (current == null || now - current.startMillis() >= WINDOW_MILLIS)
                        ? new Window(now, 1)
                        : new Window(current.startMillis(), current.count() + 1));

        if (w.count() > limit) {
            log.warn("Auth rate limit exceeded: ip={} uri={} count={}", ip,
                    request.getRequestURI(), w.count());
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Trop de tentatives. Réessayez dans une minute.\"}");
            return;
        }

        cleanupOccasionally(now);
        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Keeps the map bounded without a scheduler: purge stale windows lazily. */
    private void cleanupOccasionally(long now) {
        if (windows.size() < 10_000) return;
        for (Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().startMillis() >= WINDOW_MILLIS) {
                it.remove();
            }
        }
    }
}
