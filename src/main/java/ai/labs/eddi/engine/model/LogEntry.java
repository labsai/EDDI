/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable log entry captured from the JUL handler with MDC context. Used by
 * both the in-memory ring buffer and the DB persistence layer.
 *
 * <p>
 * {@code exception} is the record's throwable, rendered as text and redacted
 * exactly as the message is: the type and message of the exception and of each
 * {@code Caused by:} in its chain, plus the first few stack frames of each (see
 * {@code eddi.logs.exception-max-frames}). It is {@code null} — and so absent
 * from the JSON — when the record had no throwable, and for every entry stored
 * before the field existed.
 * </p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogEntry(long timestamp, String level, String loggerName, String message, String environment, String agentId, Integer agentVersion,
        String conversationId, String userId, String instanceId, String exception) {

    /** An entry without an exception. */
    public LogEntry(long timestamp, String level, String loggerName, String message, String environment, String agentId, Integer agentVersion,
            String conversationId, String userId, String instanceId) {
        this(timestamp, level, loggerName, message, environment, agentId, agentVersion, conversationId, userId, instanceId, null);
    }
}
