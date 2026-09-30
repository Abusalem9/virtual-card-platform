package com.nium.virtualcard.common.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nium.virtualcard.common.dto.ApiError;
import com.nium.virtualcard.common.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client sliding-window-counter limiter (weighted previous window + current window), which avoids the
 * 2x burst a plain fixed window allows at window boundaries. Covers every endpoint except health probes.
 * State is in-memory, so limits are per instance; see README for the distributed alternative.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 50_000;
    private static final String OVERFLOW_KEY = "__overflow__";

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int requestsPerMinute;
    private final boolean trustForwardedFor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter rejectedCounter;

    public RateLimitFilter(
            @Value("${app.rate-limit.requests-per-minute:120}") int requestsPerMinute,
            @Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.requestsPerMinute = requestsPerMinute;
        this.trustForwardedFor = trustForwardedFor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.rejectedCounter = Counter.builder("virtual_card_rate_limit_rejected_total")
                .description("Requests rejected by the API rate limiter")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/actuator/health") || uri.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long nowMillis = clock.millis();
        String key = clientKey(request);

        if (!buckets.containsKey(key) && buckets.size() >= MAX_TRACKED_CLIENTS) {
            evictIdle(nowMillis);
            if (buckets.size() >= MAX_TRACKED_CLIENTS) {
                key = OVERFLOW_KEY;
            }
        }

        if (!buckets.computeIfAbsent(key, ignored -> new Bucket()).tryAcquire(nowMillis, requestsPerMinute)) {
            rejectedCounter.increment();
            long retryAfter = Math.max(1, (WINDOW_MILLIS - nowMillis % WINDOW_MILLIS) / 1000);
            response.setStatus(ErrorCode.RATE_LIMIT_EXCEEDED.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            objectMapper.writeValue(response.getOutputStream(),
                    ApiError.of(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests. Please retry later.",
                            Instant.now(clock), request.getRequestURI()));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    @Scheduled(fixedDelay = 60_000)
    void evictOldWindows() {
        evictIdle(clock.millis());
    }

    private void evictIdle(long nowMillis) {
        long currentWindow = nowMillis / WINDOW_MILLIS;
        buckets.entrySet().removeIf(entry -> entry.getValue().lastWindow() < currentWindow - 1);
    }

    private static final class Bucket {
        private long window = Long.MIN_VALUE;
        private int current;
        private int previous;

        synchronized boolean tryAcquire(long nowMillis, int limit) {
            long index = nowMillis / WINDOW_MILLIS;
            if (index != window) {
                previous = index == window + 1 ? current : 0;
                current = 0;
                window = index;
            }
            double previousWeight = 1.0 - (nowMillis % WINDOW_MILLIS) / (double) WINDOW_MILLIS;
            if (previous * previousWeight + current + 1 > limit) {
                return false;
            }
            current++;
            return true;
        }

        synchronized long lastWindow() {
            return window;
        }
    }
}
