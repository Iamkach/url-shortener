package com.urlshortener.service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory, per-key token bucket. See specs/002-click-analytics-ratelimit/plan.md §4 for
 * why this is in-memory (single-instance prototype) rather than a shared store.
 */
@Component
public class RateLimiter {

    @Value("${app.rate-limit.capacity:20}")
    private int capacity;

    @Value("${app.rate-limit.refill-per-minute:20}")
    private int refillPerMinute;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** @return true if a token was available and consumed, false if the caller should be rejected. */
    public boolean tryConsume(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, Instant.now()));
        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void refill(Bucket bucket) {
        Instant now = Instant.now();
        double elapsedMinutes = java.time.Duration.between(bucket.lastRefill, now).toMillis() / 60000.0;
        double refillAmount = elapsedMinutes * refillPerMinute;
        if (refillAmount > 0) {
            bucket.tokens = Math.min(capacity, bucket.tokens + refillAmount);
            bucket.lastRefill = now;
        }
    }

    private static final class Bucket {
        double tokens;
        Instant lastRefill;

        Bucket(double tokens, Instant lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }
}
