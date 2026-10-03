/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How many conversations are waiting for a human, and for how long the oldest
 * has waited.
 * <p>
 * The HITL counters ({@code eddi_hitl_pause_count_total},
 * {@code eddi_hitl_resume_count_total}, …) count events, so "approvals nobody
 * answered" could only be estimated as pauses minus resumes — a difference that
 * a restart resets and that never sees pauses taken before the process started.
 * The backlog lives in the conversation store, so this reads it from there.
 * <p>
 * <b>Cost.</b> The read is the bounded, projected listing the approval inbox
 * already uses ({@link IConversationMemoryStore#findPendingApprovalSummaries}),
 * at most {@link #SCAN_LIMIT} rows, on a timer
 * ({@code eddi.hitl.metrics.refresh-interval}, default 60 s; {@code off}
 * disables it) — never on the scrape thread, so a slow store cannot stall
 * {@code /q/metrics}. Past {@link #SCAN_LIMIT} pending approvals both gauges
 * are lower bounds: the count saturates and the oldest age is the oldest among
 * the rows read.
 * <p>
 * <b>Replicas.</b> Every replica reports the same store-wide number. Aggregate
 * with {@code max}, not {@code sum}.
 * <p>
 * Both gauges read {@code NaN} until the first refresh and after a failed one —
 * "unknown" must not look like "nothing is waiting".
 */
@ApplicationScoped
public class HitlPendingMetrics {

    private static final Logger LOGGER = Logger.getLogger(HitlPendingMetrics.class);

    /** Upper bound of pending approvals read per refresh. */
    static final int SCAN_LIMIT = 1000;

    private final IConversationMemoryStore conversationMemoryStore;
    private final MeterRegistry meterRegistry;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.UNKNOWN);

    /** The last refresh's result. */
    record Snapshot(double pending, double oldestAgeSeconds) {
        static final Snapshot UNKNOWN = new Snapshot(Double.NaN, Double.NaN);
    }

    @Inject
    public HitlPendingMetrics(IConversationMemoryStore conversationMemoryStore, MeterRegistry meterRegistry) {
        this.conversationMemoryStore = conversationMemoryStore;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("eddi.hitl.pending", snapshot, s -> s.get().pending())
                .description("Conversations waiting for a human approval or input (store-wide; saturates at " + SCAN_LIMIT + ")")
                .register(meterRegistry);
        Gauge.builder("eddi.hitl.pending.oldest_age_seconds", snapshot, s -> s.get().oldestAgeSeconds())
                .description("How long the oldest pending approval has been waiting, in seconds (0 when none)")
                .register(meterRegistry);
    }

    @Scheduled(every = "${eddi.hitl.metrics.refresh-interval:60s}", delayed = "15s", identity = "hitl-pending-metrics",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        refresh(Instant.now());
    }

    /** Package-private for tests: one refresh against a fixed clock. */
    void refresh(Instant now) {
        Snapshot previous = snapshot.get();
        try {
            List<PendingApprovalSummary> pending = conversationMemoryStore.findPendingApprovalSummaries(SCAN_LIMIT);
            Instant oldest = null;
            for (PendingApprovalSummary summary : pending) {
                Instant pausedAt = summary.getPausedAt();
                if (pausedAt != null && (oldest == null || pausedAt.isBefore(oldest))) {
                    oldest = pausedAt;
                }
            }
            double age = oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis() / 1000.0);
            snapshot.set(new Snapshot(pending.size(), age));
        } catch (Exception e) {
            snapshot.set(Snapshot.UNKNOWN);
            // Once per outage, not once per refresh: a store that is down is already
            // loud elsewhere, and this would repeat every interval.
            if (!Double.isNaN(previous.pending())) {
                LOGGER.warnf("Could not count pending HITL approvals; eddi_hitl_pending reads NaN until the store answers again: %s",
                        e.getMessage());
            }
        }
    }

    /** Package-private for tests. */
    Snapshot snapshot() {
        return snapshot.get();
    }
}
