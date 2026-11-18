/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancellation;
import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancelledException;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;

/**
 * Reads a discussion's cancel signal where the work happens (K2, K8 of the
 * November 2026 review): before every member turn and every phase repeat, not
 * only at phase boundaries — a ROUND_TABLE cancelled during its "Discussion"
 * phase used to run every remaining repeat (live: 57 s and 13 model calls after
 * the cancel was acknowledged).
 * <p>
 * {@code CANCEL_GRACEFUL} lets the member turn already running finish and
 * starts nothing new. {@code CANCEL_IMMEDIATE} also releases the running turn
 * at once and stops its member conversation.
 */
public final class DiscussionCancellation {

    private DiscussionCancellation() {
    }

    /**
     * Throws {@link MemberTurnCancelledException} when the discussion was cancelled
     * — the signal {@code executeDiscussion} turns into {@code CANCELLED}. A
     * {@code null} token (no running leg on this node) never throws.
     */
    public static void checkpoint(DiscussionControlToken token) {
        if (token != null && token.isCancelled()) {
            throw new MemberTurnCancelledException();
        }
    }

    /**
     * A member turn's cancellation linked to the discussion's token: released on
     * {@code CANCEL_IMMEDIATE}, together with the member's in-flight conversation
     * turn. Close it when the turn is over.
     */
    public static Link link(DiscussionControlToken token) {
        if (token == null) {
            return new Link(null, () -> {
            });
        }
        var cancellation = new MemberTurnCancellation();
        Runnable unlink = token.onCancel(signal -> {
            if (signal == ControlSignal.CANCEL_IMMEDIATE) {
                cancellation.cancelAndStopInFlight();
            }
        });
        return new Link(cancellation, unlink);
    }

    /**
     * A linked cancellation; {@link #cancellation()} is {@code null} when there is
     * no token to link to.
     */
    public record Link(MemberTurnCancellation cancellation, Runnable unlink) implements AutoCloseable {
        @Override
        public void close() {
            unlink.run();
        }
    }
}
