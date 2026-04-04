package com.lugality.scraper.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Token-bucket–style rate limiter.
 *
 * Used by SearchService / WorkflowOrchestrator to honour the
 * {@code scraper.rate-limit-per-minute} setting (default 8 req/min).
 *
 * Usage:
 * <pre>
 *   RateLimiter limiter = new RateLimiter(8); // 8 requests per minute
 *   limiter.acquire(); // blocks until a slot is available
 * </pre>
 *
 * Equivalent to Python's time.sleep() rate-limiting in parallel_runner.py.
 */
@Slf4j
public class RateLimiter {

    private final int maxPerMinute;
    private final Deque<Instant> timestamps = new ArrayDeque<>();

    public RateLimiter(int maxRequestsPerMinute) {
        this.maxPerMinute = maxRequestsPerMinute;
    }

    /**
     * Block until a request slot is available within the rate limit window.
     */
    public synchronized void acquire() throws InterruptedException {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(60);

        // Purge timestamps older than 1 minute
        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxPerMinute) {
            // Wait until the oldest request in the window is > 60s old
            Instant oldest = timestamps.peekFirst();
            long waitMs = oldest.plusSeconds(60).toEpochMilli() - now.toEpochMilli();
            if (waitMs > 0) {
                log.debug("Rate limit reached ({} req/min). Waiting {}ms...", maxPerMinute, waitMs);
                Thread.sleep(waitMs + 50); // +50ms buffer
            }
            // Purge again after wait
            Instant after = Instant.now();
            timestamps.removeIf(t -> t.isBefore(after.minusSeconds(60)));
        }

        timestamps.addLast(Instant.now());
    }
}
