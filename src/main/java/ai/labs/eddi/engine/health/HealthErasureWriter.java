/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

/**
 * Agent Health's part in the erasure guarantee (see
 * {@link AsyncErasureWriter}).
 * <p>
 * The guarded rows are the turn rows and discard markers of
 * {@link IHealthTurnStore}: each names the user and the conversation it
 * describes, and is <em>deleted</em>, not blanked.
 * <ul>
 * <li>The barrier is the health worker's queue
 * ({@link HealthTurnRecorder}).</li>
 * <li>{@link HealthTurnRecorder} calls {@code beforeWrite} before it writes a
 * turn's row and markers and {@code afterWrite} for each of them right
 * after.</li>
 * <li>User erasure: {@link HealthTurnGdprParticipant} deletes and
 * verifies.</li>
 * </ul>
 * A row is named in a pending removal as {@code <conversationId>|<executionId>}
 * ({@link #rowId}), the same pair that keys it in both stores.
 */
@ApplicationScoped
public class HealthErasureWriter implements AsyncErasureWriter {

    /**
     * Recorded in pending removals: never rename. Also the name of
     * {@link HealthTurnGdprParticipant}, whose verification retries them.
     */
    public static final String NAME = "agentHealthTurns";

    private final HealthTurnRecorder recorder;
    private final IHealthTurnStore store;

    @Inject
    public HealthErasureWriter(HealthTurnRecorder recorder, IHealthTurnStore store) {
        this.recorder = recorder;
        this.store = store;
    }

    /** The id of the row of execution {@code executionId} of a conversation. */
    public static String rowId(String conversationId, String executionId) {
        return conversationId + "|" + executionId;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        return recorder.awaitDrained(timeout);
    }

    @Override
    public void removeRow(String rowId) {
        // Conversation ids are hex: the first separator ends one.
        int separator = rowId == null ? -1 : rowId.indexOf('|');
        if (separator <= 0 || separator == rowId.length() - 1) {
            throw new IllegalArgumentException("Not an Agent Health row id: " + rowId);
        }
        store.deleteRow(rowId.substring(0, separator), rowId.substring(separator + 1));
    }

    @Override
    public long eraseConversation(String conversationId) {
        return store.deleteByConversationId(conversationId);
    }

    @Override
    public long countForConversation(String conversationId) {
        return store.countByConversationId(conversationId);
    }
}
