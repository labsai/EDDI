/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Conversation coordinator — ensures sequential message processing per
 * conversation. Extends {@link IEventBus} to inherit the pluggable event bus
 * contract.
 *
 * <p>
 * Also provides status introspection and dead-letter management for the
 * Coordinator Dashboard (Item 5.30).
 * </p>
 *
 * @author ginccc
 */
public interface IConversationCoordinator extends IEventBus {
    // submitInOrder is inherited from IEventBus

    // ==================== Status Methods ====================

    /**
     * @return the coordinator type: "in-memory" or "nats"
     */
    default String getCoordinatorType() {
        return "in-memory";
    }

    /**
     * @return true if the coordinator is connected and operational
     */
    default boolean isConnected() {
        return true;
    }

    /**
     * @return detailed connection status string
     */
    default String getConnectionStatus() {
        return "CONNECTED";
    }

    /**
     * @return per-conversation queue depths (conversationId → number of queued
     *         tasks)
     */
    default Map<String, Integer> getQueueDepths() {
        return Collections.emptyMap();
    }

    /**
     * @return total number of tasks processed since startup
     */
    default long getTotalProcessed() {
        return 0;
    }

    /**
     * @return total number of tasks dead-lettered since startup
     */
    default long getTotalDeadLettered() {
        return 0;
    }

    /**
     * @return full status snapshot
     */
    default CoordinatorStatus getStatus() {
        return new CoordinatorStatus(getCoordinatorType(), isConnected(), getConnectionStatus(), getQueueDepths().size(), getTotalProcessed(),
                getTotalDeadLettered(), getQueueDepths());
    }

    // ==================== Dead-Letter Methods ====================

    /**
     * @return list of dead-letter entries
     */
    default List<DeadLetterEntry> getDeadLetters() {
        return Collections.emptyList();
    }

    /**
     * @return one dead-letter entry, if it exists
     */
    default Optional<DeadLetterEntry> getDeadLetter(String entryId) {
        return getDeadLetters().stream().filter(e -> e.id().equals(entryId)).findFirst();
    }

    /**
     * Removes an entry after its replay was submitted. The replay itself — a NEW
     * turn built from the entry's captured input — is performed by the admin API
     * through the conversation service; the failed task object is gone and is never
     * re-run.
     *
     * @param entryId
     *            the dead-letter entry ID
     * @return true if the entry was found and removed
     */
    default boolean replayDeadLetter(String entryId) {
        return false;
    }

    /**
     * Discard (acknowledge/remove) a dead-letter entry.
     *
     * @param entryId
     *            the dead-letter entry ID
     * @return true if the entry was found and discarded
     */
    default boolean discardDeadLetter(String entryId) {
        return false;
    }

    /**
     * Removes every dead letter of one conversation — GDPR erasure: a dead letter
     * carries the failed turn's input.
     *
     * @return the number of entries removed
     */
    default int purgeDeadLetters(String conversationId) {
        return 0;
    }

    /**
     * Purge all dead-letter entries.
     *
     * @return the number of entries purged
     */
    default int purgeDeadLetters() {
        return 0;
    }

    /**
     * Called by the graceful shutdown as soon as it starts, before the drain: a
     * coordinator stops admitting work it has not started yet. The cluster
     * coordinator fails the turns still waiting for a conversation lease, which
     * answers them 409 + Retry-After so the client retries on another node. A no-op
     * in memory, where every queued turn runs during the drain.
     */
    default void beginShutdown() {
    }

    /**
     * Called by the graceful shutdown after the drain, whether it completed or
     * timed out. The cluster coordinator releases the leases this node still holds,
     * so those conversations continue on other nodes at once.
     */
    default void completeShutdown() {
    }
}
