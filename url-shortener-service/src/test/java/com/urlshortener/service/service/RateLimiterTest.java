package com.urlshortener.service.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void tryConsume_allowsUpToCapacityThenRejects() {
        RateLimiter limiter = new RateLimiter();
        ReflectionTestUtils.setField(limiter, "capacity", 3);
        ReflectionTestUtils.setField(limiter, "refillPerMinute", 3);

        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isFalse();
    }

    @Test
    void tryConsume_tracksEachKeyIndependently() {
        RateLimiter limiter = new RateLimiter();
        ReflectionTestUtils.setField(limiter, "capacity", 1);
        ReflectionTestUtils.setField(limiter, "refillPerMinute", 1);

        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isFalse();
        assertThat(limiter.tryConsume("client-b")).isTrue();
    }
}
