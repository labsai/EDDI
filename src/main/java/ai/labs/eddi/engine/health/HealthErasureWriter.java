/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Agent Health's part in the erasure guarantee (see
 * {@link AsyncErasureWriter}).
 * <p>
 * The guarded rows are the turn rows and discard markers of
 * {@link IHealthTurnStore}, the session outcomes of {@link IHealthOutcomeStore}
 * and the business events of {@link IHealthEventStore}: each names the user or
 * the conversation it describes, and is <em>deleted</em>, not blanked.
 * <ul>
 * <li>The barrier is the health worker's queue ({@link HealthTurnRecorder}),
 * the outcome evaluations in flight ({@link HealthOutcomeEvaluator}), the event
 * matches in flight ({@link HealthEventMatcher}) and the event ingests in
 * flight ({@link HealthEventIngest}).</li>
 * <li>{@link HealthTurnRecorder} calls {@code beforeWrite} before it writes a
 * turn's row and markers and {@code afterWrite} for each of them right after;
 * {@link HealthOutcomeEvaluator} does the same for the sessions of a
 * conversation, {@link HealthEventMatcher} for the match that writes a
 * session's user onto an event, and {@link HealthEventIngest} for an event that
 * names a conversation.</li>
 * <li>User erasure: {@link HealthTurnGdprParticipant},
 * {@link HealthOutcomeGdprParticipant} and {@link HealthEventGdprParticipant}
 * each delete and verify their rows.</li>
 * </ul>
 * A turn row is named in a pending removal as
 * {@code <conversationId>|<executionId>} ({@link #rowId}), the same pair that
 * keys it in both stores; a session as
 * {@code session:<conversationId>|<sessionNo>} ({@link #sessionRowId}); an
 * event as {@code event:<eventId>} ({@link #eventRowId}). Sessions and events
 * are one writer with the turn rows, not further ones: they are derived from or
 * joined to those rows, by the same feature, in the same rollup pass, and an
 * erasure must drain and verify all of them. Their deletes mark no day dirty —
 * the daily rows hold no outcome or event counts (outcome reports and business
 * measures read the session and event rows themselves).
 * <p>
 * <b>Daily rows.</b> Every delete here marks the days of the deleted rows dirty
 * ({@link HealthDirtyDays#markDeletedRows}), so the rollup recomputes them
 * without the rows. A conversation's rows are deleted with their days
 * ({@link IHealthTurnStore#deleteConversationReturningDays}); when those cannot
 * be read, every retained day of the conversation's agent is re-rolled — read
 * from the conversation store, which still holds the conversation: the guard
 * erases these rows before the snapshot is deleted. Unlike the unguarded
 * agent-test purge, the rows are deleted even when neither the days nor the
 * agent can be read (the delete is verified to leave none); the daily rows then
 * keep their counts until {@code eddi.health.daily-retention}, with a WARN.
 * They hold counts only, no personal data.
 */
@ApplicationScoped
public class HealthErasureWriter implements AsyncErasureWriter {

    /**
     * Recorded in pending removals: never rename. Also the name of
     * {@link HealthTurnGdprParticipant}, whose verification retries them.
     */
    public static final String NAME = "agentHealthTurns";

    private static final Logger LOGGER = Logger.getLogger(HealthErasureWriter.class);

    private final HealthTurnRecorder recorder;
    private final IHealthTurnStore store;

    /** Marks the days of deleted rows dirty; null-checked. */
    private final Instance<HealthDirtyDays> dirtyDays;

    /** The fallback for a deleted conversation's agent; null-checked. */
    private final Instance<IConversationMemoryStore> conversations;

    /** The session outcomes; null-checked. */
    private final Instance<IHealthOutcomeStore> outcomes;

    /** Whose evaluations in flight the barrier waits for; null-checked. */
    private final Instance<HealthOutcomeEvaluator> evaluator;

    /** The business events; null-checked. */
    private final Instance<IHealthEventStore> events;

    /** Whose matches in flight the barrier waits for; null-checked. */
    private final Instance<HealthEventMatcher> matcher;

    /** Whose ingests in flight the barrier waits for; null-checked. */
    private final Instance<HealthEventIngest> ingest;

    @Inject
    public HealthErasureWriter(HealthTurnRecorder recorder, IHealthTurnStore store, Instance<HealthDirtyDays> dirtyDays,
            Instance<IConversationMemoryStore> conversations, Instance<IHealthOutcomeStore> outcomes, Instance<HealthOutcomeEvaluator> evaluator,
            Instance<IHealthEventStore> events, Instance<HealthEventMatcher> matcher, Instance<HealthEventIngest> ingest) {
        this.recorder = recorder;
        this.store = store;
        this.dirtyDays = dirtyDays;
        this.conversations = conversations;
        this.outcomes = outcomes;
        this.evaluator = evaluator;
        this.events = events;
        this.matcher = matcher;
        this.ingest = ingest;
    }

    /** Without the business events. */
    public HealthErasureWriter(HealthTurnRecorder recorder, IHealthTurnStore store, Instance<HealthDirtyDays> dirtyDays,
            Instance<IConversationMemoryStore> conversations, Instance<IHealthOutcomeStore> outcomes, Instance<HealthOutcomeEvaluator> evaluator) {
        this(recorder, store, dirtyDays, conversations, outcomes, evaluator, null, null, null);
    }

    /** Without the session outcomes. */
    public HealthErasureWriter(HealthTurnRecorder recorder, IHealthTurnStore store, Instance<HealthDirtyDays> dirtyDays,
            Instance<IConversationMemoryStore> conversations) {
        this(recorder, store, dirtyDays, conversations, null, null);
    }

    /** Without marking days dirty — for tests of the erasure alone. */
    public HealthErasureWriter(HealthTurnRecorder recorder, IHealthTurnStore store) {
        this(recorder, store, null, null);
    }

    /** The id of the row of execution {@code executionId} of a conversation. */
    public static String rowId(String conversationId, String executionId) {
        return conversationId + "|" + executionId;
    }

    /** Marks a session row id apart from a turn row id. */
    static final String SESSION_PREFIX = "session:";

    /** The id of session {@code sessionNo} of a conversation. */
    public static String sessionRowId(String conversationId, int sessionNo) {
        return SESSION_PREFIX + conversationId + "|" + sessionNo;
    }

    /** Marks a business event's row id apart from a turn row id. */
    static final String EVENT_PREFIX = "event:";

    /** The id of the business event {@code eventId}. */
    public static String eventRowId(String eventId) {
        return EVENT_PREFIX + eventId;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!recorder.awaitDrained(timeout)) {
            return false;
        }
        HealthOutcomeEvaluator sessions = resolved(evaluator);
        if (sessions != null && !sessions.awaitDrained(left(deadline))) {
            return false;
        }
        HealthEventMatcher matches = resolved(matcher);
        if (matches != null && !matches.awaitDrained(left(deadline))) {
            return false;
        }
        HealthEventIngest ingests = resolved(ingest);
        return ingests == null || ingests.awaitDrained(left(deadline));
    }

    private static Duration left(long deadline) {
        return Duration.ofNanos(Math.max(0L, deadline - System.nanoTime()));
    }

    @Override
    public void removeRow(String rowId) {
        if (rowId != null && rowId.startsWith(SESSION_PREFIX)) {
            removeSession(rowId);
            return;
        }
        if (rowId != null && rowId.startsWith(EVENT_PREFIX)) {
            removeEvent(rowId);
            return;
        }
        // Conversation ids are hex: the first separator ends one.
        int separator = rowId == null ? -1 : rowId.indexOf('|');
        if (separator <= 0 || separator == rowId.length() - 1) {
            throw new IllegalArgumentException("Not an Agent Health row id: " + rowId);
        }
        String conversationId = rowId.substring(0, separator);
        // Best effort, before the delete: the (agent, day) pairs of the conversation's
        // rows — a superset of the one row's, which is harmless to re-roll.
        Map<String, Set<LocalDate>> agentDays = null;
        try {
            agentDays = store.agentDaysOfConversation(conversationId);
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not read the days of an Agent Health row before its removal: %s", e.getMessage());
        }
        long deleted = store.deleteRow(conversationId, rowId.substring(separator + 1));
        if (deleted > 0) {
            // Unknown days fall back to the conversation's agent, as in eraseConversation;
            // with neither, markDeletedRows warns.
            String agentId = agentDays != null ? null : agentOf(conversationId);
            markDeleted(conversationId, agentId == null ? List.of() : List.of(agentId), new HealthDeletedRows(deleted, agentDays));
        }
    }

    /** Removes one session row ({@link #sessionRowId}); idempotent. */
    private void removeSession(String rowId) {
        String id = rowId.substring(SESSION_PREFIX.length());
        int separator = id.lastIndexOf('|');
        if (separator <= 0 || separator == id.length() - 1) {
            throw new IllegalArgumentException("Not an Agent Health session row id: " + rowId);
        }
        int sessionNo;
        try {
            sessionNo = Integer.parseInt(id.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not an Agent Health session row id: " + rowId, e);
        }
        IHealthOutcomeStore sessions = resolved(outcomes);
        if (sessions == null) {
            // Never report a removal that did not run: it stays pending.
            throw new IllegalStateException("No Agent Health outcome store to remove a session from");
        }
        sessions.deleteSessions(id.substring(0, separator), List.of(sessionNo));
    }

    /** Removes one business event ({@link #eventRowId}); idempotent. */
    private void removeEvent(String rowId) {
        String id = rowId.substring(EVENT_PREFIX.length());
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Not an Agent Health event row id: " + rowId);
        }
        IHealthEventStore businessEvents = resolved(events);
        if (businessEvents == null) {
            // Never report a removal that did not run: it stays pending.
            throw new IllegalStateException("No Agent Health event store to remove an event from");
        }
        businessEvents.delete(List.of(id));
    }

    @Override
    public long eraseConversation(String conversationId) {
        long erased = eraseTurns(conversationId);
        IHealthOutcomeStore sessions = resolved(outcomes);
        if (sessions != null) {
            erased += sessions.deleteByConversationId(conversationId);
        }
        IHealthEventStore businessEvents = resolved(events);
        if (businessEvents != null) {
            // Matched and pending alike: a pending event naming the conversation names
            // it too.
            erased += businessEvents.deleteByConversationId(conversationId);
        }
        return erased;
    }

    private long eraseTurns(String conversationId) {
        HealthDeletedRows deleted = store.deleteConversationReturningDays(conversationId);
        if (deleted == null) {
            // Nothing to mark; countForConversation still verifies the rows are gone.
            return 0;
        }
        if (deleted.deleted() > 0) {
            // The agent only when the days are unknown: the guard erases these rows
            // before the conversation's snapshot is deleted, so it can still be read.
            String agentId = deleted.daysKnown() ? null : agentOf(conversationId);
            markDeleted(conversationId, agentId == null ? List.of() : List.of(agentId), deleted);
        }
        return deleted.deleted();
    }

    private static <T> T resolved(Instance<T> instance) {
        return instance == null || !instance.isResolvable() ? null : instance.get();
    }

    private void markDeleted(String conversationId, List<String> agentIds, HealthDeletedRows deleted) {
        if (dirtyDays == null || !dirtyDays.isResolvable()) {
            return;
        }
        try {
            dirtyDays.get().markDeletedRows(conversationId, agentIds, deleted);
        } catch (RuntimeException e) {
            // Never fail the erasure over derived counts: the rows themselves are gone.
            LOGGER.warnf("Could not mark the days of the deleted Agent Health rows of conversation %s dirty: %s",
                    sanitize(conversationId), sanitize(e.getMessage()));
        }
    }

    /**
     * The conversation's agent, best effort; {@code null} when it cannot be read.
     */
    private String agentOf(String conversationId) {
        if (conversations == null || !conversations.isResolvable()) {
            return null;
        }
        try {
            Map<String, ConversationListingSummary> summaries = conversations.get().loadListingSummaries(List.of(conversationId));
            ConversationListingSummary summary = summaries == null ? null : summaries.get(conversationId);
            return summary == null ? null : summary.agentId();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public long countForConversation(String conversationId) {
        IHealthOutcomeStore sessions = resolved(outcomes);
        IHealthEventStore businessEvents = resolved(events);
        return store.countByConversationId(conversationId) + (sessions == null ? 0L : sessions.countByConversationId(conversationId))
                + (businessEvents == null ? 0L : businessEvents.countByConversationId(conversationId));
    }
}
