/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.continuation.ConversationContinuations.Recovery;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.PendingContinuationCandidate;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Re-arms self-continuations that were decided and committed but never armed,
 * because the node stopped between the commit and the schedule write (#1180).
 * <p>
 * Every turn that decides to continue stores a {@code PendingContinuation}
 * outbox record in the same document write as its step (see
 * {@link ConversationContinuations}). This sweep reads the records older than
 * {@code eddi.continuation.recovery.grace-period} — by then the turn's own arm,
 * which runs milliseconds after its commit, has long happened or never will —
 * oldest first, at most {@code eddi.continuation.recovery.batch-size} per pass,
 * from an index that holds only the records. Each one is handed to
 * {@link ConversationContinuations#recover}, which writes the schedule only if
 * it is missing and then removes the record. A healthy job's record is removed
 * by the first pass that finds its schedule, so the index stays the size of the
 * jobs in flight.
 * <p>
 * <b>Cluster.</b> Correctness does not depend on one node sweeping: the
 * schedule id is derived from the continuation, so two nodes write one row
 * between them, and the record is removed conditionally on its number. The
 * {@code leader.continuation-recovery} lease only saves the other nodes the
 * reads; on a single node it is always granted.
 */
@ApplicationScoped
public class ContinuationRecoverySweep {

    private static final Logger LOGGER = Logger.getLogger(ContinuationRecoverySweep.class);
    static final String LEADER_KEY = IConversationLeaseManager.LEADER + "continuation-recovery";

    private final IConversationMemoryStore memoryStore;
    private final ConversationContinuations continuations;
    private final IConversationLeaseManager leases;
    private final boolean enabled;
    private final Duration gracePeriod;
    private final int batchSize;
    private final Clock clock;

    @Inject
    public ContinuationRecoverySweep(IConversationMemoryStore memoryStore, ConversationContinuations continuations,
            IConversationLeaseManager leases,
            @ConfigProperty(name = "eddi.continuation.recovery.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.continuation.recovery.grace-period", defaultValue = "60s") Duration gracePeriod,
            @ConfigProperty(name = "eddi.continuation.recovery.batch-size", defaultValue = "100") int batchSize) {
        this(memoryStore, continuations, leases, enabled, gracePeriod, batchSize, Clock.systemUTC());
    }

    ContinuationRecoverySweep(IConversationMemoryStore memoryStore, ConversationContinuations continuations, IConversationLeaseManager leases,
            boolean enabled, Duration gracePeriod, int batchSize, Clock clock) {
        this.memoryStore = memoryStore;
        this.continuations = continuations;
        this.leases = leases;
        this.enabled = enabled;
        this.gracePeriod = gracePeriod != null && !gracePeriod.isNegative() ? gracePeriod : Duration.ofSeconds(60);
        this.batchSize = batchSize > 0 ? batchSize : 100;
        this.clock = clock;
    }

    @Scheduled(every = "${eddi.continuation.recovery.interval:60s}", delayed = "30s", identity = "continuation-recovery",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledSweep() {
        if (enabled) {
            sweep();
        }
    }

    /**
     * One pass. Never throws: a store that cannot be read is logged and the next
     * pass tries again; one candidate that fails does not stop the others.
     *
     * @return how many candidates ended in each outcome (empty when another node
     *         holds the lease or nothing was due)
     */
    public Map<Recovery, Integer> sweep() {
        Map<Recovery, Integer> outcomes = new EnumMap<>(Recovery.class);
        Optional<LeaseHandle> lease;
        try {
            lease = leases.tryAcquireKey(LEADER_KEY);
        } catch (RuntimeException e) {
            LOGGER.debugf("Continuation recovery skipped: the leader lease could not be read (%s)", e.getMessage());
            return outcomes;
        }
        if (lease.isEmpty()) {
            return outcomes;
        }
        try {
            List<PendingContinuationCandidate> candidates = memoryStore
                    .findPendingContinuations(clock.millis() - gracePeriod.toMillis(), batchSize);
            for (PendingContinuationCandidate candidate : candidates) {
                try {
                    outcomes.merge(continuations.recover(candidate), 1, Integer::sum);
                } catch (Exception e) {
                    LOGGER.warnf("Could not recover the pending continuation of conversation %s: %s",
                            sanitize(candidate.conversationId()), e.toString());
                }
            }
            if (outcomes.containsKey(Recovery.RECOVERED)) {
                LOGGER.infof("Continuation recovery pass: %s", outcomes);
            }
        } catch (Exception e) {
            LOGGER.warnf("Continuation recovery pass failed: %s", e.toString());
        } finally {
            try {
                leases.release(lease.get());
            } catch (RuntimeException e) {
                LOGGER.debugf("Could not release the continuation recovery lease: %s", e.getMessage());
            }
        }
        return outcomes;
    }
}
