/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.health.model.HealthTurn;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * {@link UserDataExport#SECTION_TRUNCATED}. Daily aggregates (a later phase)
 * hold no personal data and are not part of either.
 */
@ApplicationScoped
public class HealthTurnGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many rows. */
    static final int MAX_EXPORTED_TURNS = 10_000;

    private final IHealthTurnStore store;
    private final ErasureGuard erasureGuard;

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
        long deleted = store.deleteByUserId(userId);
        if (erasureGuard != null) {
            erasureGuard.verifyUserErased(HealthErasureWriter.NAME, userId, () -> store.countByUserId(userId));
        }
        return deleted;
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
