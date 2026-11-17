/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthReportRateLimiter.Charge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthReportRateLimiter — lookups per client and overall, reads per link and overall")
class HealthReportRateLimiterTest {

    @Test
    @DisplayName("a link gets 30 reads a minute, refilled continuously; another link has its own bucket")
    void perLink() {
        AtomicLong clock = new AtomicLong(0);
        HealthReportRateLimiter limiter = new HealthReportRateLimiter(clock::get);
        for (int i = 0; i < HealthReportRateLimiter.PER_LINK_PER_MINUTE; i++) {
            assertTrue(limiter.tryRead("h1"), "read " + i);
        }
        assertFalse(limiter.tryRead("h1"));
        assertTrue(limiter.tryRead("h2"));
        clock.addAndGet(2_000_000_000L); // two seconds: one token back
        assertTrue(limiter.tryRead("h1"));
        assertFalse(limiter.tryRead("h1"));
    }

    @Test
    @DisplayName("found reports share 300 a minute")
    void global() {
        AtomicLong clock = new AtomicLong(0);
        HealthReportRateLimiter limiter = new HealthReportRateLimiter(clock::get);
        for (int i = 0; i < HealthReportRateLimiter.GLOBAL_PER_MINUTE; i++) {
            assertTrue(limiter.tryRead("h" + i));
        }
        assertFalse(limiter.tryRead("another"));
        clock.addAndGet(60_000_000_000L);
        assertTrue(limiter.tryRead("another"));
    }

    @Test
    @DisplayName("lookups: 10 a minute per client, 60 overall; a found link gets its charge back and is free from then on")
    void lookups() {
        AtomicLong clock = new AtomicLong(0);
        HealthReportRateLimiter limiter = new HealthReportRateLimiter(clock::get);
        for (int i = 0; i < HealthReportRateLimiter.CLIENT_LOOKUPS_PER_MINUTE; i++) {
            assertTrue(limiter.tryLookup("miss" + i, "10.0.0.1").allowed());
        }
        assertFalse(limiter.tryLookup("miss-x", "10.0.0.1").allowed(), "one client is capped");
        // Other clients fill the shared bucket.
        int used = HealthReportRateLimiter.CLIENT_LOOKUPS_PER_MINUTE;
        for (int c = 2; used < HealthReportRateLimiter.LOOKUPS_PER_MINUTE; c++) {
            for (int i = 0; i < HealthReportRateLimiter.CLIENT_LOOKUPS_PER_MINUTE && used < HealthReportRateLimiter.LOOKUPS_PER_MINUTE; i++) {
                assertTrue(limiter.tryLookup("m" + c + "-" + i, "10.0.0." + c).allowed());
                used++;
            }
        }
        assertFalse(limiter.tryLookup("fresh", "10.9.9.9").allowed(), "the shared lookup bucket is empty");

        // A hash found earlier is never charged.
        clock.addAndGet(60_000_000_000L);
        Charge first = limiter.tryLookup("real", "10.9.9.9");
        assertTrue(first.allowed() && first.charged());
        limiter.found("real", first);
        for (int i = 0; i < 100; i++) {
            Charge again = limiter.tryLookup("real", "10.9.9.9");
            assertTrue(again.allowed() && !again.charged(), "known hash " + i);
        }
        limiter.missed("real");
        assertTrue(limiter.tryLookup("real", "10.9.9.9").charged(), "revoked: charged again");
    }

    @Test
    @DisplayName("unknown and malformed tokens cannot starve a valid link")
    void scanCannotStarveALink() {
        AtomicLong clock = new AtomicLong(0);
        HealthReportRateLimiter limiter = new HealthReportRateLimiter(clock::get);
        Charge c = limiter.tryLookup("owner", "203.0.113.5");
        limiter.found("owner", c);
        // A scan from many addresses drains every lookup bucket.
        for (int i = 0; i < 10_000; i++) {
            limiter.tryLookup("junk" + i, "198.51.100." + (i % 250));
        }
        // The owner still reads: known hash, own bucket, and nothing of the scan
        // counted against reads.
        for (int i = 0; i < HealthReportRateLimiter.PER_LINK_PER_MINUTE; i++) {
            assertTrue(limiter.tryLookup("owner", "203.0.113.5").allowed());
            assertTrue(limiter.tryRead("owner"), "read " + i);
        }
    }
}
