/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;

import java.util.Collection;
import java.util.Map;

/**
 * The {@code finishReason} a client is told for one turn, read from the turn's
 * output ({@link MemoryKeys#TURN_FINISH_REASON}).
 * <p>
 * {@code length} and {@code content_filter} stand as recorded. {@code empty} —
 * written by an LLM task that got no text — is a statement about the whole turn
 * only when the turn shows nothing at all: when another task produced output
 * text or quick replies, the turn is not empty and no reason is reported.
 *
 * @since 6.7.0
 */
public final class TurnFinishReason {

    private TurnFinishReason() {
    }

    /** The reason to report for {@code output}, or {@code null} for none. */
    public static String of(ConversationOutput output) {
        if (output == null) {
            return null;
        }
        Object reason = output.get(MemoryKeys.TURN_FINISH_REASON);
        if (!(reason instanceof String text) || text.isBlank()) {
            return null;
        }
        if (MemoryKeys.TURN_FINISH_REASON_EMPTY.equals(text) && showsSomething(output)) {
            return null;
        }
        return text;
    }

    /** Whether the turn has output items or quick replies a client renders. */
    static boolean showsSomething(Map<String, Object> output) {
        for (var entry : output.entrySet()) {
            String key = entry.getKey();
            if (key == null || !(key.startsWith(MemoryKeys.OUTPUT_PREFIX) || key.startsWith(MemoryKeys.QUICK_REPLIES_PREFIX))) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Collection<?> items ? !items.isEmpty() : value instanceof String text ? !text.isBlank() : value != null) {
                return true;
            }
        }
        return false;
    }
}
