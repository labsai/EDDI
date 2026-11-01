/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps alert work that was queued before a GDPR erasure from writing the
 * erased user's content afterwards.
 * <p>
 * Alert evaluation runs on a queue: a turn's output, or a rating, captured
 * before the erasure can reach the store after the erasure's sweep blanked
 * everything stored for the user. So the erasure's step 0
 * ({@link #stopInFlightWork}, and {@link #stopInFlightWorkByHash} on every
 * other node through the cluster stop broadcast) records a <b>fence</b>: the
 * time the erasure started, per user. Content captured before that time is not
 * stored; an event written while the fence went up is blanked before it is
 * delivered.
 * <p>
 * A fence is node-local, keyed by the hash of the user id (the form the cluster
 * broadcast carries), and expires after {@link #FENCE_TTL} — far longer than
 * the alert queue takes to drain, and short enough that a later conversation of
 * a reused id is never affected (only content captured <i>before</i> the fence
 * is ever held back anyway).
 */
@ApplicationScoped
public class AlertErasureFence implements UserErasureParticipant {

    /** How long a fence lasts after the erasure started. */
    static final Duration FENCE_TTL = Duration.ofMinutes(15);

    private final Map<String, Instant> fences = new ConcurrentHashMap<>();
    private final Clock clock;

    public AlertErasureFence() {
        this(Clock.systemUTC());
    }

    AlertErasureFence(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String erasureStepName() {
        return "alertErasureFence";
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
        // Nothing is interrupted; queued work checks the fence when it runs.
        return 0;
    }

    /**
     * Whether content of {@code userId} captured at {@code capturedAt} falls before
     * a live erasure fence and must not be stored.
     */
    public boolean covers(String userId, Instant capturedAt) {
        if (userId == null || fences.isEmpty()) {
            return false;
        }
        Instant start = fences.get(KvKeys.sha256(userId));
        if (start == null || !start.plus(FENCE_TTL).isAfter(clock.instant())) {
            return false;
        }
        return capturedAt == null || !capturedAt.isAfter(start);
    }
}
