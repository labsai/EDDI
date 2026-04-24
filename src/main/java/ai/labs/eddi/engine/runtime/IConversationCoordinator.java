/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
     * Replay a dead-letter entry (re-inject into the main processing workflow).
     *
     * @param entryId
     *            the dead-letter entry ID
     * @return true if the entry was found and replayed
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
     * Purge all dead-letter entries.
     *
     * @return the number of entries purged
     */
    default int purgeDeadLetters() {
        return 0;
    }
}
