/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthEventCount;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * The business events of Agent Health (H5) — MongoDB {@code healthevents},
 * PostgreSQL {@code health_events}; the deployment's datastore picks one
 * ({@code DataStoreProducers}). One row per event id. Every method throws
 * {@link HealthStoreException} when the store fails.
 */
public interface IHealthEventStore {

    /**
     * Stores new events; an id already stored is left as it is (a redelivery).
     *
     * @return how many were new
     */
    int insert(Collection<HealthEvent> events);

    /** Pending events, oldest receipt first, at most {@code limit}. */
    List<HealthEvent> findPending(int limit);

    /**
     * Records a match — only while the event is still pending.
     *
     * @return whether it was recorded
     */
    boolean markMatched(HealthEvent matched);

    /** Deletes events by id. */
    long delete(Collection<String> ids);

    /**
     * Deletes event {@code id} only while it names {@code conversationId} — the
     * erasure guard's removal of one event of one conversation, which must not
     * reach a row of another conversation that happens to share the id (a sender
     * reusing its event id).
     *
     * @return how many were deleted (0 or 1)
     */
    long deleteInConversation(String id, String conversationId);

    /**
     * Deletes pending events received before {@code before} (unmatched in time).
     */
    long deletePendingReceivedBefore(Instant before);

    /** Pending events of one agent — the per-agent cap. */
    long countPending(String agentId);

    /** The matched events of a conversation, oldest first. */
    List<HealthEvent> findMatchedByConversation(String conversationId);

    /**
     * Matched events of one agent on the query's days (of {@code at}), versions and
     * origins, counted per event type and evidence — and per day when
     * {@code perDay}.
     */
    List<HealthEventCount> counts(HealthUsageQuery query, boolean perDay);

    /**
     * A user's matched events, newest first, at most {@code limit} — GDPR export.
     */
    List<HealthEvent> findByUserId(String userId, int limit);

    /** Deletes a user's matched events (GDPR erasure). */
    long deleteByUserId(String userId);

    /**
     * Deletes the events of a conversation (it was deleted): those matched to it
     * and the pending ones that name it.
     */
    long deleteByConversationId(String conversationId);

    /**
     * How many matched events name {@code userId} — the verification of a GDPR
     * erasure. Reads committed data from the primary: a lagging replica must not
     * answer "none left".
     */
    long countByUserId(String userId);

    /**
     * How many events — matched or pending — name {@code conversationId}: the
     * verification of a permanent conversation delete. Committed, like
     * {@link #countByUserId}.
     */
    long countByConversationId(String conversationId);

    /** Deletes the events that happened before {@code day} (retention). */
    long deleteOlderThan(LocalDate day);
}
