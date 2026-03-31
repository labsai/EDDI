package ai.labs.eddi.engine.model;

import java.util.Map;

/**
 * Status snapshot of the conversation coordinator.
 *
 * @param coordinatorType
 *            "in-memory" or "nats"
 * @param connected
 *            true if the coordinator is operational
 * @param connectionStatus
 *            detailed connection status string
 * @param activeConversations
 *            number of conversations with queued tasks
 * @param totalProcessed
 *            total tasks processed since startup
 * @param totalDeadLettered
 *            total dead-lettered tasks since startup
 * @param queueDepths
 *            per-conversation queue depths (conversationId → depth)
 */
public record CoordinatorStatus(String coordinatorType, boolean connected, String connectionStatus, int activeConversations, long totalProcessed,
        long totalDeadLettered, Map<String, Integer> queueDepths) {
}
