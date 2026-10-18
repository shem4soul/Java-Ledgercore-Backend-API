package com.ledgercore.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.common.ProblemFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiter for the unauthenticated /auth endpoints (brute-force and credential-stuffing protection).
 * In-memory, so it is per instance; put a shared store (Redis) or WAF/ALB rules in front when scaling out.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(long startMs, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final Clock clock;
    private final ObjectMapper mapper;

    public RateLimitFilter(int limitPerMinute, Clock clock, ObjectMapper mapper) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        if (windows.size() > 10_000) windows.entrySet().removeIf(e -> now - e.getValue().startMs() > 60_000);
        Window w = windows.merge(req.getRemoteAddr(), new Window(now, 1),
                (old, fresh) -> now - old.startMs() >= 60_000 ? fresh : new Window(old.startMs(), old.count() + 1));
        if (w.count() > limitPerMinute) {
            long retry = Math.max(1, (60_000 - (now - w.startMs()) + 999) / 1000);
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setHeader("Retry-After", String.valueOf(retry));
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(),
                    ProblemFactory.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Try again later."));
            return;
        }
        chain.doFilter(req, res);
    }
}
