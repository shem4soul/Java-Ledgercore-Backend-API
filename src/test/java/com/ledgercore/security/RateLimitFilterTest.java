package com.ledgercore.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    /** A clock the test can move forward. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private int call(RateLimitFilter filter, String path, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRequestURI(path);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void blocksAfterTheLimit_perIp_thenRecoversInTheNextWindow() throws Exception {
        MutableClock clock = new MutableClock();
        RateLimitFilter f = new RateLimitFilter(3, clock, new ObjectMapper());
        for (int i = 0; i < 3; i++) assertThat(call(f, "/api/v1/auth/login", "10.0.0.1")).isEqualTo(200);
        assertThat(call(f, "/api/v1/auth/login", "10.0.0.1")).isEqualTo(429);
        assertThat(call(f, "/api/v1/auth/login", "10.0.0.2")).as("other clients are unaffected").isEqualTo(200);

        clock.advance(Duration.ofSeconds(61));
        assertThat(call(f, "/api/v1/auth/login", "10.0.0.1")).as("window reset").isEqualTo(200);
    }

    @Test
    void onlyAppliesToAuthEndpoints() throws Exception {
        RateLimitFilter f = new RateLimitFilter(1, new MutableClock(), new ObjectMapper());
        for (int i = 0; i < 10; i++) assertThat(call(f, "/api/v1/accounts", "10.0.0.1")).isEqualTo(200);
    }

    @Test
    void tooManyRequests_returnsProblemJsonWithRetryAfter() throws Exception {
        MutableClock clock = new MutableClock();
        RateLimitFilter f = new RateLimitFilter(1, clock, new ObjectMapper());
        call(f, "/api/v1/auth/login", "10.0.0.9");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setRequestURI("/api/v1/auth/login");
        req.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        assertThat(res.getContentAsString()).contains("RATE_LIMITED");
    }
}
