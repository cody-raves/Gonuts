package dev.doughbay.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void staysWithinBudgetUsingFakeClock() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(180, 220, clock::get);

        // 180 requests in the same instant are allowed...
        for (int i = 0; i < 180; i++) {
            assertEquals(0, limiter.millisUntilPermitted());
            limiter.acquire();
        }
        // ...the 181st must wait for the window to roll.
        assertTrue(limiter.millisUntilPermitted() > 0);
        assertEquals(180, limiter.requestsInLastMinute());

        // After the window passes, requests flow again.
        clock.addAndGet(60_001);
        assertEquals(0, limiter.millisUntilPermitted());
        assertEquals(0, limiter.requestsInLastMinute());
    }

    @Test
    void redactedKeyNeverShowsFullSecret() {
        DonutApiConfig config = DonutApiConfig.withDefaults("super-secret-api-key-123");
        assertTrue(!config.redactedKey().contains("secret"));
        assertTrue(config.redactedKey().contains("***"));
    }
}
