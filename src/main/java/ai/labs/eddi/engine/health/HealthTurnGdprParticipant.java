/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins GDPR erasure and export for the Agent Health turn rows.
 * <p>
 * A row names the user whose conversation it describes, together with what the
 * turn did (its actions, cost, failures) — operational data about that person's
 * use, so it is erased and exported with the rest of their data. Erasure
 * deletes every row whose {@code userId} is the erased user; that covers all of
 * their conversations, including those of their group discussions (a member
 * conversation is owned by the discussion's user).
 * <p>
 * <b>The erasure guarantee.</b> A row still waiting in the health queue when
 * the erasure runs does not land after it: the erasure tombstones the user and
 * their conversations and waits for every node's health queue to drain
 * ({@link HealthErasureWriter}), and {@link HealthTurnRecorder} guards each
 * write with {@link ErasureGuard#beforeWrite} and
 * {@link ErasureGuard#afterWrite}. After deleting,
 * {@link ErasureGuard#verifyUserErased} retries the user's pending removals and
 * requires no row, no marker and no pending removal to be left; otherwise the
 * step fails and a re-run finishes it.
 * <p>
 * A conversation deleted on its own ({@code ConversationPurgeService}) or by
 * the conversation-retention sweep takes its rows with it, through the same
 * guarantee ({@link HealthErasureWriter#eraseConversation}).
 * <p>
 * The export carries the user's rows, newest first, under {@code turns}; past
 * {@link #MAX_EXPORTED_TURNS} the section is marked
 * {@link UserDataExport#SECTION_TRUNCATED}. Daily aggregates hold no personal
 * data and are not part of either; the days of the erased rows are marked
 * dirty, so the rollup recomputes them without the user. The rows of the user's
 * own conversations are normally gone already — the cascade erases them through
 * {@link HealthErasureWriter#eraseConversation} before the snapshots are
 * deleted, which marks their days with the agent known — so this delete takes
 * what is left. Its days come with the delete
 * ({@link IHealthTurnStore#deleteByUserIdReturningDays}); when they cannot be
 * read the delete still runs, and every retained day of the agents of any
 * conversation of the user still in the conversation store is re-rolled. When
 * none is known nothing can be re-rolled: a WARN, and the daily rows keep the
 * user's counts until {@code eddi.health.daily-retention}. That does not fail
 * the step — the rows, the only personal data, are gone.
 */
@ApplicationScoped
public class HealthTurnGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many rows. */
    static final int MAX_EXPORTED_TURNS = 10_000;

    /** The most conversations of an erased user whose agents are read. */
    static final int MAX_CONVERSATIONS = 10_000;

    private static final int BATCH = 500;

    private final IHealthTurnStore store;
    private final ErasureGuard erasureGuard;

    /**
     * Marks the days of the erased rows dirty, so the daily rows lose the user's
     * counts at the next rollup. Field-injected and null-checked: tests build this
     * class by hand.
     */
    @Inject
    Instance<HealthDirtyDays> dirtyDays;

    /** The fallback for the user's agents. Field-injected, null-checked. */
    @Inject
    Instance<IConversationMemoryStore> conversations;

    @Inject
    public HealthTurnGdprParticipant(IHealthTurnStore store, ErasureGuard erasureGuard) {
        this.store = store;
        this.erasureGuard = erasureGuard;
    }

    /** Without the erasure guard: tests of the delete and the export alone. */
    HealthTurnGdprParticipant(IHealthTurnStore store) {
        this(store, null);
    }

    @Override
    public String name() {
        return HealthErasureWriter.NAME;
    }

    @Override
    public long erase(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        HealthDeletedRows deleted = store.deleteByUserIdReturningDays(userId);
        long count = deleted == null ? 0L : deleted.deleted();
        if (dirtyDays != null && dirtyDays.isResolvable() && count > 0) {
            dirtyDays.get().markErasedRows(deleted.daysKnown() ? Set.of() : agentsOf(userId), deleted);
        }
        // Verified whatever the delete answered: the count is what decides.
        if (erasureGuard != null) {
            erasureGuard.verifyUserErased(HealthErasureWriter.NAME, userId, () -> store.countByUserId(userId));
        }
        return count;
    }

    /**
     * The erased user's agents when the deleted rows' days are unknown: those of
     * the user's conversations still in the conversation store (best effort).
     */
    private Set<String> agentsOf(String userId) {
        if (conversations != null && conversations.isResolvable()) {
            try {
                return agentsOfConversations(conversations.get(), userId);
            } catch (Exception e) {
                // Nothing else to try; markErasedRows warns.
            }
        }
        return Set.of();
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
    public Object export(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        // One more than the cap, to know whether the cap cut anything off.
        List<HealthTurn> held = store.findByUserId(userId, MAX_EXPORTED_TURNS + 1);
        if (held.isEmpty()) {
            return null;
        }
        boolean truncated = held.size() > MAX_EXPORTED_TURNS;
        List<HealthTurn> exported = truncated ? held.subList(0, MAX_EXPORTED_TURNS) : held;
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("turns", exported.stream().map(HealthTurnGdprParticipant::exported).toList());
        section.put(UserDataExport.SECTION_TRUNCATED, truncated);
        return section;
    }

    /**
     * A row as plain JSON values — dates as ISO strings, so the export does not
     * depend on the mapper knowing {@code java.time}.
     */
    static Map<String, Object> exported(HealthTurn t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", t.conversationId());
        row.put("stepIndex", t.stepIndex());
        row.put("executionId", t.executionId());
        row.put("agentId", t.agentId());
        row.put("agentVersion", t.agentVersion());
        row.put("origin", t.origin());
        row.put("day", t.day() == null ? null : t.day().toString());
        row.put("timestamp", t.timestamp() == null ? null : t.timestamp().toString());
        row.put("costUsd", t.costUsd());
        row.put("totalTokens", t.totalTokens());
        row.put("durationMs", t.durationMs());
        row.put("errorKind", t.errorKind());
        row.put("paused", t.paused());
        row.put("failures", t.failures().stream().map(f -> {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("class", f.failureClass());
            failure.put("model", f.model());
            failure.put("recovery", f.recovery());
            return failure;
        }).toList());
        row.put("fallbackServed", t.fallbackServed());
        row.put("toolCalls", t.toolCalls());
        row.put("toolFailures", t.toolFailures());
        row.put("toolRefusals", t.toolRefusals());
        row.put("guardrailTrips", t.guardrailTrips());
        row.put("userInput", t.userInput());
        row.put("actions", t.actions());
        row.put("discarded", t.discarded());
        return row;
    }
}
