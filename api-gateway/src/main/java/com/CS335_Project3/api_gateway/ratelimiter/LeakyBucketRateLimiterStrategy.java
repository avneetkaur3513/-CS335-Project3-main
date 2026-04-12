package com.CS335_Project3.api_gateway.ratelimiter;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class LeakyBucketRateLimiterStrategy implements RateLimiterStrategy {

    private static class BucketState {
        double water;
        long lastUpdateMs;

        BucketState(double water, long lastUpdateMs) {
            this.water = water;
            this.lastUpdateMs = lastUpdateMs;
        }
    }

    private final Map<String, BucketState> buckets = new HashMap<>();

    // 1 request drained every second
    private static final double LEAK_PER_MS = 1.0 / 1000.0;

    @Override
    public synchronized boolean isRequestAllowed(String clientId, int limit) {
        long now = System.currentTimeMillis();
        BucketState state = buckets.computeIfAbsent(clientId, id -> new BucketState(0.0, now));

        long elapsed = now - state.lastUpdateMs;
        if (elapsed > 0) {
            state.water = Math.max(0.0, state.water - (elapsed * LEAK_PER_MS));
            state.lastUpdateMs = now;
        }

        if (state.water + 1.0 > limit) {
            return false;
        }

        state.water += 1.0;
        return true;
    }
}
