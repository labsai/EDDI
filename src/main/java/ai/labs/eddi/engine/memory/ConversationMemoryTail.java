/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;

import java.util.List;

/**
 * The current state of a conversation without its history: what a read that
 * only shows "where the conversation is now" needs, loaded without transferring
 * the whole stored document.
 * <p>
 * A conversation of an agent with tools stores 0.5–3 MB after twenty turns, and
 * almost all of it is the step history. The current-step read of a chat reload
 * and the approval-status read of a pending approval card used to load all of
 * it to show one step; on a bandwidth-limited database link that took tens of
 * seconds. The stores answer this read with a server-side projection instead.
 * <p>
 * What {@link #snapshot()} holds:
 * <ul>
 * <li>every top-level field of the stored document, unchanged — header, state,
 * the HITL pause bookmark, {@code conversationProperties}</li>
 * <li>{@code conversationSteps} and {@code conversationOutputs}: at most their
 * LAST element (both empty when the conversation has none). The full totals are
 * {@link #stepCount()} and {@link #outputCount()}</li>
 * <li>{@code redoCache}: empty — only its size, {@link #redoCount()}, and its
 * spend (inside {@link #conversationCost()}) are carried</li>
 * <li>{@code hitlPendingToolCalls}: possibly without the resume machinery —
 * {@code chatTranscriptJson}, {@code gatingAssistantMessageJson},
 * {@code traceSoFar}, {@code fingerprint} and each call's {@code argumentsRaw}.
 * No read surface serves those (the simple projection copies an allow-list
 * without them, and the approver projection nulls them), and the frozen
 * transcript is often the largest field of a paused document</li>
 * </ul>
 * A store that has no cheaper read may hand back the full snapshot through
 * {@link #ofFullSnapshot} — every consumer therefore reads only the LAST
 * element of the two lists and never relies on the dropped fields being absent.
 * <p>
 * Read-only: never persist it, resume from it, or hand it to anything that
 * walks the history (undo/redo, the conversation log, export). Those keep
 * loading the full snapshot.
 *
 * @param snapshot
 *            the projected document, see above
 * @param stepCount
 *            how many steps the stored conversation has
 * @param outputCount
 *            how many outputs the stored conversation has
 * @param redoCount
 *            how many steps the stored redo cache holds
 * @param conversationCost
 *            the conversation's total spend exactly as
 *            {@link ConversationMemoryUtilities#conversationCost(List, List)}
 *            reports it for the full snapshot; {@code null} when no step
 *            carried one
 */
public record ConversationMemoryTail(ConversationMemorySnapshot snapshot, int stepCount, int outputCount, int redoCount, Double conversationCost) {

    /**
     * The tail of a fully loaded snapshot — the fallback for a store, or a document
     * shape, that the projected read does not cover. Holds the snapshot itself, not
     * a copy.
     */
    public static ConversationMemoryTail ofFullSnapshot(ConversationMemorySnapshot snapshot) {
        return new ConversationMemoryTail(snapshot, snapshot.getConversationSteps().size(), snapshot.getConversationOutputs().size(),
                snapshot.getRedoCache().size(),
                ConversationMemoryUtilities.conversationCost(snapshot.getConversationSteps(), snapshot.getRedoCache()));
    }

    /**
     * The tail of a projected read whose store extracted the spend entries' values
     * ({@code audit:cost} and {@code audit:discarded_cost} results, in document
     * order) instead of sending the steps.
     *
     * @param liveSpendValues
     *            the values found in {@code conversationSteps}
     * @param redoSpendValues
     *            the values found in {@code redoCache}
     */
    public static ConversationMemoryTail ofProjection(ConversationMemorySnapshot snapshot, int stepCount, int outputCount, int redoCount,
                                                      List<?> liveSpendValues, List<?> redoSpendValues) {
        return new ConversationMemoryTail(snapshot, stepCount, outputCount, redoCount,
                ConversationMemoryUtilities.combineCost(ConversationMemoryUtilities.sumSpend(liveSpendValues),
                        ConversationMemoryUtilities.sumSpend(redoSpendValues)));
    }

    /**
     * Whether {@link #snapshot()} still holds every step — true for a tail built
     * from a full snapshot, and for a conversation of at most one step.
     */
    public boolean holdsAllSteps() {
        return snapshot.getConversationSteps().size() == stepCount;
    }
}
