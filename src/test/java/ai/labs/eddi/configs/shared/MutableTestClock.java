/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock the test moves by hand — for turn-deadline tests that must not wait
 * for real time.
 */
public final class MutableTestClock extends Clock {
    private final AtomicLong now = new AtomicLong(1_000_000L);

    public void advance(long ms) {
        now.addAndGet(ms);
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
        return Instant.ofEpochMilli(now.get());
    }

    @Override
    public long millis() {
        return now.get();
    }
}
