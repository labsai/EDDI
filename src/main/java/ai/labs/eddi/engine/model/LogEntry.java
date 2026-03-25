package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable log entry captured from the JUL handler with MDC context. Used by
 * agenth the in-memory ring buffer and the DB persistence layer.
 *
 * @author ginccc
 * @since 6.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogEntry(long timestamp, String level, String loggerName, String message, String environment, String agentId, Integer agentVersion,
        String conversationId, String userId, String instanceId) {
}
