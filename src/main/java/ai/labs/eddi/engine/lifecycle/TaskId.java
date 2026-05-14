/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Immutable identifier for lifecycle tasks in the EDDI conversation pipeline.
 *
 * <p>
 * TaskId provides a type-safe wrapper around task identifiers with a canonical
 * URI format ({@code eddi://ai.labs.taskname}). It ensures consistent
 * identifier handling across the system and provides clean JSON serialization.
 * </p>
 *
 * <p>
 * <strong>Usage:</strong>
 * </p>
 *
 * <pre>{@code
 * TaskId parserTask = new TaskId("ai.labs.parser");
 * String identifier = parserTask.getIdentifier(); // "eddi://ai.labs.parser"
 * String name = parserTask.name(); // "ai.labs.parser"
 * }</pre>
 *
 * <p>
 * <strong>JSON Serialization:</strong>
 * </p>
 * <ul>
 * <li><strong>Serialize:</strong> {@code "eddi://ai.labs.parser"} (via
 * {@link #getIdentifier()})</li>
 * <li><strong>Deserialize:</strong> Accepts both
 * {@code "eddi://ai.labs.parser"} and {@code "ai.labs.parser"}</li>
 * </ul>
 *
 * @param name
 *            the task identifier (e.g., "ai.labs.parser", "ai.labs.behavior")
 * @author EDDI Team
 * @since 6.0
 * @see ILifecycleTask#getId()
 */
public record TaskId(String name) {

    /**
     * URI scheme prefix for all task identifiers. Full identifiers have the format
     * {@code eddi://<name>}.
     */
    private static final String SCHEME = "eddi://";

    /**
     * Returns the full URI identifier for this task.
     *
     * <p>
     * This is the canonical string representation used for:
     * <ul>
     * <li>JSON serialization (via {@link JsonValue})</li>
     * <li>Component cache lookups</li>
     * <li>Logging and debugging</li>
     * <li>OpenTelemetry span attributes</li>
     * </ul>
     * </p>
     *
     * @return the full URI identifier (e.g., "eddi://ai.labs.parser")
     */
    @JsonValue
    public String getIdentifier() {
        return SCHEME + name;
    }

    /**
     * Returns the full URI identifier.
     *
     * <p>
     * Delegates to {@link #getIdentifier()} for consistent string representation.
     * </p>
     *
     * @return the full URI identifier
     */
    @Override
    public String toString() {
        return getIdentifier();
    }

    /**
     * Factory method for deserializing TaskId from a string value.
     *
     * <p>
     * Accepts both formats:
     * <ul>
     * <li>Full URI: {@code "eddi://ai.labs.parser"}</li>
     * <li>Bare type: {@code "ai.labs.parser"}</li>
     * </ul>
     * </p>
     *
     * @param value
     *            the string to deserialize (must not be null)
     * @return a new TaskId instance
     * @throws IllegalArgumentException
     *             if value is null
     */
    @JsonCreator
    public static TaskId fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TaskId cannot be null");
        }

        if (value.startsWith(SCHEME)) {
            return new TaskId(value.substring(SCHEME.length()));
        }

        return new TaskId(value);
    }
}
