/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The turn-execution ids on conversation steps
 * ({@link MemoryKeys#HEALTH_EXECUTION_ID},
 * {@link MemoryKeys#HEALTH_DISCARDED_EXECUTIONS}). Called by
 * {@code Conversation}; never throws into the turn.
 * <p>
 * An execution id is minted at the end of a step's first execution and kept
 * from then on, so everything that continues the same step — a HITL resume, a
 * rerun — writes to the same Agent Health row, and a say after an undo, which
 * runs on a new step, starts a new one. The ids of the undone steps are carried
 * onto that new step, at the point the undo history is dropped, so the recorder
 * can mark their rows discarded.
 */
public final class TurnExecutions {

    /** At most this many discarded execution ids are carried onto one step. */
    static final int MAX_DISCARDED = 100;

    private TurnExecutions() {
    }

    /**
     * The execution ids of {@code abandoned} steps — the redo cache a new turn is
     * about to drop. Call before the cache is cleared.
     */
    public static List<String> executionIdsOf(Collection<? extends IConversationStep> abandoned) {
        List<String> ids = new ArrayList<>();
        try {
            if (abandoned != null) {
                for (IConversationStep step : abandoned) {
                    String id = executionId(step);
                    if (id != null && ids.size() < MAX_DISCARDED) {
                        ids.add(id);
                    }
                }
            }
        } catch (RuntimeException e) {
            // Best effort: a missed discard only over-counts one turn.
        }
        return ids;
    }

    /**
     * Gives {@code step} an execution id unless it has one, and records which
     * executions its turn discarded. Call at the end of each execution of the step.
     *
     * @param discarded
     *            the ids {@link #executionIdsOf} returned when this step's turn
     *            started, or {@code null}/empty
     */
    public static void stamp(IWritableConversationStep step, List<String> discarded) {
        if (step == null) {
            return;
        }
        try {
            if (executionId(step) == null) {
                step.storeData(new Data<>(MemoryKeys.HEALTH_EXECUTION_ID, UUID.randomUUID().toString()));
            }
            if (discarded != null && !discarded.isEmpty() && step.getData(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS) == null) {
                step.storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.copyOf(discarded)));
            }
        } catch (RuntimeException e) {
            // Never fail a turn over a health id; the recorder falls back to a legacy id.
        }
    }

    /** The step's execution id, or {@code null}. */
    public static String executionId(IConversationStep step) {
        if (step == null) {
            return null;
        }
        IData<Object> data = step.getData(MemoryKeys.HEALTH_EXECUTION_ID);
        return data != null && data.getResult() instanceof String id && !id.isBlank() ? id : null;
    }

    /** The execution ids the step's turn discarded; empty when none. */
    public static List<String> discardedBy(IConversationStep step) {
        if (step == null) {
            return List.of();
        }
        IData<Object> data = step.getData(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS);
        if (data == null || !(data.getResult() instanceof List<?> list)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(list.size());
        for (Object id : list) {
            if (id != null) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }
}
