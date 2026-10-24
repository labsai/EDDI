/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

/**
 * What an alert rule listens to. Each source reads its own fields of
 * {@link AlertCondition}; a rule that sets another source's fields is refused
 * at save time.
 * <p>
 * {@code OUTPUT_MATCH} (a condition on what a turn returned) follows in a later
 * release; it will add its fields to {@link AlertCondition} and a constant
 * here, nothing else changes shape.
 */
public enum AlertSource {
    /** A rating was created or changed. */
    RATING,
    /**
     * A turn ended in an error, was interrupted, exceeded its deadline, or was
     * stopped by a cost limit.
     */
    CONVERSATION_ERROR
}
