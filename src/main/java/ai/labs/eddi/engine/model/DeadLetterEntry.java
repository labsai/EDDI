package ai.labs.eddi.engine.model;

/**
 * A single dead-letter entry representing a failed conversation task.
 *
 * @param id             unique identifier (NATS sequence number or in-memory index)
 * @param conversationId the conversation this task belonged to
 * @param error          the error message from the final failure
 * @param timestamp      epoch millis when the task was dead-lettered
 * @param payload        full JSON payload (for inspection)
 */
public record DeadLetterEntry(
        String id,
        String conversationId,
        String error,
        long timestamp,
        String payload
) {
}
