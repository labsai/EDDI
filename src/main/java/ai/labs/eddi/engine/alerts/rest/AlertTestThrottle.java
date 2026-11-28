/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.rest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How often one caller may send a rule's test alert: each test posts to up to
 * every target of the rule at once, so without a bound the test endpoint is a
 * way to flood a Slack channel or a webhook. A fixed one-minute window per
 * caller, {@code eddi.alerts.test-max-per-minute} sends in it (default 6;
 * {@code 0} or less turns the bound off). Per node: a cluster multiplies it by
 * its size, which is fine for a bound on accidents and casual abuse.
 */
@ApplicationScoped
public class AlertTestThrottle {

    static final int DEFAULT_MAX_PER_MINUTE = 6;

    private final int maxPerMinute;
    private final Cache<String, AtomicInteger> windows;

    @Inject
    public AlertTestThrottle(@ConfigProperty(name = "eddi.alerts.test-max-per-minute", defaultValue = "6") int maxPerMinute) {
        this(maxPerMinute, Ticker.systemTicker());
    }

    AlertTestThrottle(int maxPerMinute, Ticker ticker) {
        this.maxPerMinute = maxPerMinute;
        this.windows = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(1)).ticker(ticker).build();
    }

    /** Whether {@code caller} may send one more test now (and counts it). */
    public boolean tryAcquire(String caller) {
        if (maxPerMinute <= 0) {
            return true;
        }
        AtomicInteger used = windows.get(caller == null ? "" : caller, key -> new AtomicInteger());
        return used.incrementAndGet() <= maxPerMinute;
    }

    public int maxPerMinute() {
        return maxPerMinute;
    }
}
