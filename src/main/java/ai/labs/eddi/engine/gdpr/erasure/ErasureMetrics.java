/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

/**
 * The meters of the erasure guarantee ({@code eddi.gdpr.guard.*}). No tag
 * carries a user or conversation id; {@code writer} is the bounded set of
 * {@link AsyncErasureWriter#name()}s.
 */
@ApplicationScoped
public class ErasureMetrics {

    static final String WRITES_SKIPPED = "eddi.gdpr.guard.writes.skipped";
    static final String SELF_DELETIONS = "eddi.gdpr.guard.self.deletions";
    static final String PENDING_REMOVALS = "eddi.gdpr.guard.pending.removals";
    static final String DRAIN = "eddi.gdpr.guard.drain";
    static final String DRAIN_TIMEOUTS = "eddi.gdpr.guard.drain.timeouts";

    static final String REASON_TOMBSTONE = "tombstone";
    static final String REASON_CHECK_FAILED = "check_failed";
    static final String PENDING_RECORDED = "recorded";
    static final String PENDING_CLEARED = "cleared";
    static final String PENDING_RECORD_FAILED = "record_failed";

    private final MeterRegistry registry;

    @Inject
    public ErasureMetrics(MeterRegistry registry) {
        this.registry = registry;
        // At zero from the start, so the first one after a restart is a visible rise.
        skipped0(REASON_TOMBSTONE);
        skipped0(REASON_CHECK_FAILED);
        for (String outcome : new String[]{PENDING_RECORDED, PENDING_CLEARED, PENDING_RECORD_FAILED}) {
            pending0(outcome);
        }
        drainTimer();
    }

    void writeSkipped(String reason) {
        skipped0(reason).increment();
    }

    void selfDeleted(String writer) {
        Counter.builder(SELF_DELETIONS).description("Rows an async writer removed again because an erasure started while it wrote them")
                .tag("writer", writer).register(registry).increment();
    }

    void pendingRemoval(String outcome) {
        pending0(outcome).increment();
    }

    void drained(Duration waited) {
        drainTimer().record(waited);
    }

    void drainTimedOut(String writer) {
        Counter.builder(DRAIN_TIMEOUTS).description("Erasure drain barriers an async writer did not pass in time").tag("writer", writer)
                .register(registry).increment();
    }

    private Counter skipped0(String reason) {
        return Counter.builder(WRITES_SKIPPED).description("Guarded writes skipped because their user or conversation was erased")
                .tag("reason", reason).register(registry);
    }

    private Counter pending0(String outcome) {
        return Counter.builder(PENDING_REMOVALS).description("Pending removals of erased rows: recorded, cleared, or not recordable")
                .tag("outcome", outcome).register(registry);
    }

    private Timer drainTimer() {
        return Timer.builder(DRAIN).description("Time an erasure waited for the async writers of this node to drain").register(registry);
    }
}
