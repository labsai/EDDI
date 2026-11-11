/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>
 * <b>The erased user's agents.</b> On the node running the erasure, step 0 also
 * notes the agents of the user's conversations, read from the conversation
 * store while those still exist (the cascade deletes them before the
 * {@link HealthTurnGdprParticipant} runs). They are the participant's fallback
 * when the days of the user's deleted rows cannot be read: every retained day
 * of those agents is then re-rolled. Best effort — a failed read notes nothing
 * and never fails step 0 — and kept, like the fence, for {@link #FENCE_TTL}.
 */
@ApplicationScoped
public class HealthErasureFence implements UserErasureParticipant {

    /** How long a fence lasts after the erasure started. */
    static final Duration FENCE_TTL = Duration.ofMinutes(15);

    /** The most conversations of an erased user whose agents are read. */
    static final int MAX_CONVERSATIONS = 10_000;

    private static final Logger LOGGER = Logger.getLogger(HealthErasureFence.class);
    private static final int BATCH = 500;

    private final Map<String, Instant> fences = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> agents = new ConcurrentHashMap<>();

    /** Field-injected and null-checked: tests build this class by hand. */
    @Inject
    Instance<IConversationMemoryStore> conversations;
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
        String hash = KvKeys.sha256(userId);
        int stopped = stopInFlightWorkByHash(hash);
        if (conversations != null && conversations.isResolvable()) {
            try {
                Set<String> found = agentsOfConversations(conversations.get(), userId);
                if (!found.isEmpty()) {
                    agents.put(hash, found);
                }
            } catch (Exception e) {
                // Best effort: only the fallback of a failed day lookup depends on it.
                LOGGER.debugf("Could not read the agents of an erased user's conversations: %s", e.getMessage());
            }
        }
        return stopped;
    }

    /**
     * The agents of {@code userId}'s conversations noted at step 0 of a running
     * erasure; empty when none were (or could be) read.
     */
    public Set<String> agentsOf(String userId) {
        if (userId == null) {
            return Set.of();
        }
        return agents.getOrDefault(KvKeys.sha256(userId), Set.of());
    }

    /**
     * The distinct agent ids of the user's conversations — at most
     * {@link #MAX_CONVERSATIONS} of them, read in batches. Throws when the store
     * cannot answer.
     */
    static Set<String> agentsOfConversations(IConversationMemoryStore store, String userId) throws Exception {
        List<String> ids = store.getConversationIdsByUserId(userId);
        Set<String> found = new LinkedHashSet<>();
        if (ids == null || ids.isEmpty()) {
            return found;
        }
        List<String> capped = ids.size() > MAX_CONVERSATIONS ? ids.subList(0, MAX_CONVERSATIONS) : ids;
        for (int from = 0; from < capped.size(); from += BATCH) {
            Map<String, ConversationListingSummary> summaries = store
                    .loadListingSummaries(capped.subList(from, Math.min(capped.size(), from + BATCH)));
            if (summaries == null) {
                continue;
            }
            for (ConversationListingSummary summary : summaries.values()) {
                if (summary != null && summary.agentId() != null && !summary.agentId().isBlank()) {
                    found.add(summary.agentId());
                }
            }
        }
        return found;
    }

    @Override
    public int stopInFlightWorkByHash(String userIdHash) {
        if (userIdHash == null || userIdHash.isBlank()) {
            return 0;
        }
        Instant now = clock.instant();
        fences.values().removeIf(start -> !start.plus(FENCE_TTL).isAfter(now));
        agents.keySet().retainAll(fences.keySet());
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
