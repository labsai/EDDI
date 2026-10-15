/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import java.util.Map;

/**
 * Status snapshot of the conversation coordinator.
 *
 * @param coordinatorType
 *            "in-memory" or "nats"
 * @param connected
 *            true if the coordinator is operational (in cluster mode: connected
 *            to NATS)
 * @param connectionStatus
 *            detailed connection status string
 * @param activeConversations
 *            number of conversations with queued tasks
 * @param totalProcessed
 *            total tasks processed since startup
 * @param totalDeadLettered
 *            total dead-lettered tasks since startup
 * @param queueDepths
 *            per-conversation queue depths (conversationId → depth) on this
 *            node
 * @param nodeId
 *            the node that answered; {@code null} in in-memory mode
 * @param cluster
 *            cluster view in NATS mode — {@code members} (presence records),
 *            {@code leasesHeld}, {@code natsStatus}, {@code degraded},
 *            {@code degradedSince} and, with {@code ?scope=cluster}, every
 *            member's {@code queueDepths}; {@code null} in in-memory mode
 */
public record CoordinatorStatus(String coordinatorType, boolean connected, String connectionStatus, int activeConversations, long totalProcessed,
        long totalDeadLettered, Map<String, Integer> queueDepths, String nodeId, Map<String, Object> cluster) {

    /** The single-node shape. */
    public CoordinatorStatus(String coordinatorType, boolean connected, String connectionStatus, int activeConversations, long totalProcessed,
            long totalDeadLettered, Map<String, Integer> queueDepths) {
        this(coordinatorType, connected, connectionStatus, activeConversations, totalProcessed, totalDeadLettered, queueDepths, null, null);
    }
}
