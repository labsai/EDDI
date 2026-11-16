/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.events;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

/**
 * CDI event fired when a paused (HITL) conversation resume completes and the
 * conversation is NOT re-paused — i.e. the human (or the timeout policy)
 * decided and the resumed turn produced its final outcome.
 * <p>
 * The engine fires this event <b>asynchronously</b> ({@code Event.fireAsync})
 * so that a slow observer can never block the resume pipeline, and so the
 * engine stays decoupled from any specific delivery channel (Slack, Teams, SSE,
 * …). Observers MUST treat their own failures as best-effort — a broken
 * observer must never affect the resume, which has already been persisted by
 * the time this event fires.
 * <p>
 * Timeout-driven and cancellation-driven resolutions flow through the same
 * event: {@link #decidedBy()} carries a {@code system:*} identifier (e.g.
 * {@code system:timeout}) in those cases, letting observers distinguish
 * automated outcomes from human ones.
 *
 * @param conversationId
 *            the resumed conversation
 * @param verdict
 *            the decision verdict (APPROVED / REJECTED), or {@code null} if
 *            unknown
 * @param decidedBy
 *            who decided — a principal identifier, or a {@code system:*} marker
 *            for automated decisions
 * @param snapshot
 *            the post-resume conversation snapshot (state is NOT
 *            {@code AWAITING_HUMAN}); observers extract the continuation output
 *            from it
 * @param pauseId
 *            the identity of the pause this decision ended, or {@code null}
 *            when the resolution did not come from a decision on one pause (a
 *            cancel). The event is observed asynchronously and the conversation
 *            may be paused again by then, so an observer that shows the outcome
 *            must not attribute it to any other pause
 */
public record HitlResumeCompletedEvent(
        String conversationId,
        HitlVerdict verdict,
        String decidedBy,
        SimpleConversationMemorySnapshot snapshot,
        String pauseId) {

    /** An event that does not say which pause it ended. */
    public HitlResumeCompletedEvent(String conversationId, HitlVerdict verdict, String decidedBy,
            SimpleConversationMemorySnapshot snapshot) {
        this(conversationId, verdict, decidedBy, snapshot, null);
    }
}
