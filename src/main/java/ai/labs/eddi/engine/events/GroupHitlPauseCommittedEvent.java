/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.events;

/**
 * CDI event fired when a group discussion's pause for human approval has been
 * <b>durably committed</b> — the group conversation is
 * {@code AWAITING_APPROVAL} in the store, its pause fields written and its
 * timeout armed. The group counterpart of {@link HitlPauseCommittedEvent}.
 * <p>
 * Fired asynchronously ({@code Event.fireAsync}) after the commit, whatever
 * started the discussion — Slack, a schedule, a team cadence, REST or MCP — so
 * an observer can reach approvers the starting channel's own listener does not.
 * Never fired for a pause restored after a failed resume: that is the same
 * pause, already announced.
 * <p>
 * The event carries identifiers only. An observer reads the group conversation
 * from the store and must re-check that it is still in this pause: by the time
 * an async observer runs, a reviewer may already have decided, or a cancel may
 * have turned the pause into a cancellation. Observers are best-effort: a
 * failing one must never affect the pause.
 *
 * @param groupConversationId
 *            the paused group conversation
 * @param pauseId
 *            the pause's id ({@code HitlDecision.pauseIdOf(pausedAt)})
 * @since 6.8.0
 */
public record GroupHitlPauseCommittedEvent(String groupConversationId, String pauseId) {
}
