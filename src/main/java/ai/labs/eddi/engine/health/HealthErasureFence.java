/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps Agent Health turn rows that were queued before a GDPR erasure from
 * landing after it — the {@code AlertErasureFence} pattern, for the health
 * worker.
 * <p>
 * {@link HealthTurnRecorder} writes on its own queue, so a turn the user
 * finished just before asking to be erased can reach the store after
 * {@link HealthTurnGdprParticipant#erase} deleted their rows. The erasure's
 * step 0 ({@link #stopInFlightWork}, and {@link #stopInFlightWorkByHash} on
 * every other node through the cluster stop broadcast) therefore records a
 * <b>fence</b> per user, before anything is deleted. The worker checks it
 * before writing a row or a discard marker (and then writes neither), and again
 * after the write — an erasure that started while the write was in progress may
 * have deleted before the row existed, so the row is deleted again then.
 * <p>
 * Unlike the alert fence, which holds back only content captured before the
 * erasure started, this one covers <em>every</em> capture of the user while it
 * lasts: a turn that was running when the erasure started finishes after it,
 * and its row is the user's data all the same. A row holds no content, so the
 * price — a reused user id recording nothing for {@link #FENCE_TTL} — is only
 * telemetry.
 * <p>
 * A fence is node-local, keyed by the hash of the user id (the form the cluster
 * broadcast carries), and expires after {@link #FENCE_TTL}. A row that waits in
 * the queue longer than that, or is written on a node that did not confirm the
 * cluster stop (the erasure then reports {@code clusterStop} as failed), is
 * still caught by the recorder's second check: its conversation was deleted by
 * the erasure, and a row written for a conversation that no longer exists is
 * deleted after the write.
 */
@ApplicationScoped
public class HealthErasureFence implements UserErasureParticipant {

    /** How long a fence lasts after the erasure started. */
    static final Duration FENCE_TTL = Duration.ofMinutes(15);

    private final Map<String, Instant> fences = new ConcurrentHashMap<>();
    private final Clock clock;

    public HealthErasureFence() {
        this(Clock.systemUTC());
    }

    HealthErasureFence(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String erasureStepName() {
        return "agentHealthErasureFence";
    }

    @Override
    public int stopInFlightWork(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        return stopInFlightWorkByHash(KvKeys.sha256(userId));
    }

    @Override
    public int stopInFlightWorkByHash(String userIdHash) {
        if (userIdHash == null || userIdHash.isBlank()) {
            return 0;
        }
        Instant now = clock.instant();
        fences.values().removeIf(start -> !start.plus(FENCE_TTL).isAfter(now));
        fences.put(userIdHash, now);
        // Nothing is interrupted; the health worker checks the fence when it writes.
        return 0;
    }

    /** Whether an erasure of {@code userId} started within {@link #FENCE_TTL}. */
    public boolean covers(String userId) {
        if (userId == null || fences.isEmpty()) {
            return false;
        }
        Instant start = fences.get(KvKeys.sha256(userId));
        return start != null && start.plus(FENCE_TTL).isAfter(clock.instant());
    }
}
