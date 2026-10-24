/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.events;

/**
 * CDI event fired when a regular (1:1) conversation's pause for human approval
 * has been <b>durably committed</b> — the conversation is
 * {@code AWAITING_HUMAN} in the store, its bookmark written, its timeout armed.
 * The counterpart of {@link HitlResumeCompletedEvent}.
 * <p>
 * Fired asynchronously ({@code Event.fireAsync}) from the three places a pause
 * is committed — a turn, the {@code CONVERSATION_START} turn, and a resume that
 * pauses again — and only after the commit, so an observer that loads the
 * conversation finds the pause it was told about. Never fired for a pause that
 * is restored after a failed resume: that is the same pause, already announced.
 * <p>
 * The event carries identifiers only. An observer reads the pause from the
 * store and must re-check that it is still current: by the time an async
 * observer runs, a reviewer may already have decided. Observers are
 * best-effort: a failing one must never affect the pause.
 *
 * @param conversationId
 *            the paused conversation
 * @param pauseId
 *            the pause's id ({@code HitlDecision.pauseIdOf(hitlPausedAt)})
 */
public record HitlPauseCommittedEvent(String conversationId, String pauseId) {
}
