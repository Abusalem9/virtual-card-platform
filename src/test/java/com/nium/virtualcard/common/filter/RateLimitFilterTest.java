package com.nium.virtualcard.common.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {
    private static final Instant WINDOW_START = Instant.parse("2026-09-29T12:00:00Z");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void rejectsRequestsAboveConfiguredLimit() throws Exception {
        RateLimitFilter filter = filter(2, false, Clock.fixed(WINDOW_START, ZoneOffset.UTC));

        assertEquals(200, execute(filter, "127.0.0.1", null).getStatus());
        assertEquals(200, execute(filter, "127.0.0.1", null).getStatus());

        MockHttpServletResponse rejected = execute(filter, "127.0.0.1", null);
        assertEquals(429, rejected.getStatus());
        assertEquals("60", rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("\"code\":\"RATE_LIMIT_EXCEEDED\""));
        assertEquals(1.0, meterRegistry.counter("virtual_card_rate_limit_rejected_total").count());
    }

    @Test
    void clientsAreLimitedIndependently() throws Exception {
        RateLimitFilter filter = filter(1, false, Clock.fixed(WINDOW_START, ZoneOffset.UTC));

        assertEquals(200, execute(filter, "10.0.0.1", null).getStatus());
        assertEquals(429, execute(filter, "10.0.0.1", null).getStatus());
        assertEquals(200, execute(filter, "10.0.0.2", null).getStatus());
    }

    @Test
    void limitResetsAfterTheWindowPasses() throws Exception {
        MutableClock clock = new MutableClock(WINDOW_START);
        RateLimitFilter filter = filter(2, false, clock);

        execute(filter, "127.0.0.1", null);
        execute(filter, "127.0.0.1", null);
        assertEquals(429, execute(filter, "127.0.0.1", null).getStatus());

        clock.set(WINDOW_START.plusSeconds(150));
        assertEquals(200, execute(filter, "127.0.0.1", null).getStatus());
    }

    @Test
    void previousWindowUsageStillCountsEarlyInTheNextWindow() throws Exception {
        MutableClock clock = new MutableClock(WINDOW_START.plusSeconds(59));
        RateLimitFilter filter = filter(2, false, clock);

        execute(filter, "127.0.0.1", null);
        execute(filter, "127.0.0.1", null);

        // One second into the next window the old window still carries ~98% of its weight, so a fixed
        // window would wrongly allow a fresh burst here.
        clock.set(WINDOW_START.plusSeconds(61));
        assertEquals(429, execute(filter, "127.0.0.1", null).getStatus());
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() throws Exception {
        RateLimitFilter filter = filter(1, false, Clock.fixed(WINDOW_START, ZoneOffset.UTC));

        assertEquals(200, execute(filter, "127.0.0.1", "1.1.1.1").getStatus());
        assertEquals(429, execute(filter, "127.0.0.1", "2.2.2.2").getStatus());
    }

    @Test
    void forwardedForIsUsedWhenTrusted() throws Exception {
        RateLimitFilter filter = filter(1, true, Clock.fixed(WINDOW_START, ZoneOffset.UTC));

        assertEquals(200, execute(filter, "127.0.0.1", "1.1.1.1, 9.9.9.9").getStatus());
        assertEquals(200, execute(filter, "127.0.0.1", "2.2.2.2").getStatus());
        assertEquals(429, execute(filter, "127.0.0.1", "1.1.1.1").getStatus());
    }

    @Test
    void healthProbesAreNotLimited() throws Exception {
        RateLimitFilter filter = filter(1, false, Clock.fixed(WINDOW_START, ZoneOffset.UTC));

        for (int i = 0; i < 5; i++) {
            assertEquals(200, execute(filter, "GET", "/actuator/health", "127.0.0.1", null).getStatus());
        }
    }

    @Test
    void nonApiEndpointsAreLimitedToo() throws Exception {
        RateLimitFilter filter = filter(1, false, Clock.fixed(WINDOW_START, ZoneOffset.UTC));

        assertEquals(200, execute(filter, "GET", "/actuator/metrics", "127.0.0.1", null).getStatus());
        MockHttpServletResponse rejected = execute(filter, "GET", "/actuator/metrics", "127.0.0.1", null);
        assertEquals(429, rejected.getStatus());
        assertNotNull(rejected.getContentAsString());
    }

    private RateLimitFilter filter(int limit, boolean trustForwardedFor, Clock clock) {
        return new RateLimitFilter(limit, trustForwardedFor, new ObjectMapper().findAndRegisterModules(), clock, meterRegistry);
    }

    private MockHttpServletResponse execute(RateLimitFilter filter, String remoteAddr, String forwardedFor) throws Exception {
        return execute(filter, "GET", "/api/v1/cards/test", remoteAddr, forwardedFor);
    }

    private MockHttpServletResponse execute(RateLimitFilter filter, String method, String uri, String remoteAddr,
                                            String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
