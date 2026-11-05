/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

/**
 * Who breaks a tied (or quorum-failed) vote under
 * {@code tiePolicy: HUMAN_DECIDES}, how long they have, and what happens when
 * they do not answer.
 * <p>
 * The pause rides the human-member input machinery
 * ({@code AWAITING_HUMAN_INPUT}), not the approval path: a tie-break is a
 * choice among options, which the binary approve/reject endpoints cannot carry,
 * and only the named decider (or an admin) may answer — an
 * {@code eddi-approver} cannot set the outcome of someone else's vote.
 *
 * @param decider
 *            the principal id that decides. When absent, the group's single
 *            HUMAN member decides; a group with none or several must name one
 *            (refused at save time). Need not be a member — like the
 *            facilitator's {@code escalateTo}
 * @param timeout
 *            ISO-8601 duration the decider has (e.g. {@code PT24H}). Absent =
 *            {@code humanMemberConfig.turnTimeout}; when that is absent too the
 *            pause waits indefinitely
 * @param onTimeout
 *            what a timeout resolves to. {@code NO_DECISION} (default) records
 *            the honest NONE and carries on; {@code MODERATOR_DECIDES} lets the
 *            moderator choose (refused at save time without a moderator);
 *            {@code ABORT} cancels the discussion
 */
public record HumanTieBreakConfig(String decider, String timeout, OnTieBreakTimeout onTimeout) {

    /** What an unanswered tie-break resolves to. */
    public enum OnTieBreakTimeout {
        NO_DECISION, MODERATOR_DECIDES, ABORT
    }

    /** Blank strings are absent; a missing policy is NO_DECISION. */
    public HumanTieBreakConfig {
        decider = decider == null || decider.isBlank() ? null : decider.trim();
        timeout = timeout == null || timeout.isBlank() ? null : timeout.trim();
        onTimeout = onTimeout == null ? OnTieBreakTimeout.NO_DECISION : onTimeout;
    }

    /**
     * All defaults: the single HUMAN member decides, no own timeout, NO_DECISION.
     */
    public HumanTieBreakConfig() {
        this(null, null, OnTieBreakTimeout.NO_DECISION);
    }
}
