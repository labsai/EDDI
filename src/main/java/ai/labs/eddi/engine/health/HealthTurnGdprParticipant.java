/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.health.model.HealthTurn;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * A row still waiting in the health queue when the erasure runs does not land
 * after it: the erasure's step 0 raises a {@link HealthErasureFence} on every
 * node, and {@link HealthTurnRecorder} withholds a fenced user's rows and
 * markers, and deletes again a row it wrote while the fence went up.
 * <p>
 * A conversation deleted on its own ({@code ConversationPurgeService}) takes
 * its rows with it. One deleted by the conversation-retention sweep does not
 * need to: rows are kept {@code eddi.health.facts-retention} (90 days by
 * default), far less than the conversation retention (365 days), and
 * {@link HealthTurnRetention} removes them on its own.
 * <p>
 * The export carries the user's rows, newest first, under {@code turns}; past
 * {@link #MAX_EXPORTED_TURNS} the section is marked
 * {@link UserDataExport#SECTION_TRUNCATED}. Daily aggregates hold no personal
 * data and are not part of either; the days of the erased rows are marked
 * dirty, so the rollup recomputes them without the user.
 */
@ApplicationScoped
public class HealthTurnGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many rows. */
    static final int MAX_EXPORTED_TURNS = 10_000;

    /** The most (agent, day) pairs of an erased user re-rolled. */
    static final int MAX_ERASED_DAYS = 10_000;

    private final IHealthTurnStore store;

    /**
     * Marks the days of the erased rows dirty, so the daily rows lose the user's
     * counts at the next rollup. Field-injected and null-checked: tests build this
     * class by hand.
     */
    @Inject
    Instance<HealthDirtyDays> dirtyDays;

    @Inject
    public HealthTurnGdprParticipant(IHealthTurnStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "agentHealthTurns";
    }

    @Override
    public long erase(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        Map<String, Set<LocalDate>> days = Map.of();
        try {
            days = store.agentDaysOfUser(userId, MAX_ERASED_DAYS);
        } catch (RuntimeException e) {
            // The erasure itself must not depend on it.
        }
        long deleted = store.deleteByUserId(userId);
        if (dirtyDays != null && dirtyDays.isResolvable()) {
            dirtyDays.get().noteAll(days);
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
