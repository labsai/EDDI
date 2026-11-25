/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Erasure guards for tests of code that needs one but is not about the
 * guarantee itself — a service built by hand without a guard refuses every
 * permanent delete.
 */
public final class TestErasureGuards {

    private TestErasureGuards() {
    }

    /** A guard over an in-memory store, with no async writer to drain. */
    public static ErasureGuard withoutWriters() {
        return new ErasureGuard(new InMemoryErasureTombstoneStore(), List.of(), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                Duration.ofSeconds(1), Duration.ofMinutes(5), Clock.systemUTC());
    }
}
