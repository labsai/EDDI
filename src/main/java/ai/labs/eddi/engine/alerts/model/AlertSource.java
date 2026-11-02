/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

/**
 * What an alert rule listens to. Each source reads its own fields of
 * {@link AlertCondition}; a rule that sets another source's fields is refused
 * at save time.
 */
public enum AlertSource {
    /** A rating was created or changed. */
    RATING,
    /**
     * A turn ended in an error, was interrupted, exceeded its deadline, or was
     * stopped by a cost limit.
     */
    CONVERSATION_ERROR,
    /** A turn completed and a value in its result matches the rule's constant. */
    OUTPUT_MATCH
}
