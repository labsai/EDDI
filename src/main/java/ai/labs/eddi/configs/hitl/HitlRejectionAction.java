/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * What a single-agent RULE pause does with a {@code REJECTED} decision
 * ({@code hitlConfig.onRejection}).
 * <ul>
 * <li>{@code SKIP} (default, the behaviour before 6.6): the remaining pipeline
 * tasks are skipped and the reviewer's note is shown.</li>
 * <li>{@code REVISE}: a rejection that carries a note asks the same model to
 * revise the answer it gave before the pause, with the note as the corrective
 * message, and the pipeline runs again from the task after the model's. A
 * rejection without a note, an automated one, or one with no answer to revise
 * still skips.</li>
 * </ul>
 * Tool-call pauses are unaffected: a rejected tool call already goes back to
 * the model as a tool result carrying the note.
 *
 * @since 6.6.0
 */
public enum HitlRejectionAction {
    SKIP, REVISE;

    /**
     * Case-insensitive, like every other HITL value on the wire. An unknown value
     * fails deserialization, so it is a save-time 400.
     */
    @JsonCreator
    public static HitlRejectionAction fromString(String value) {
        if (value == null) {
            return null;
        }
        return HitlRejectionAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
