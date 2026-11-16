/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * What kind of answer a pending human input asks for, when it is not a plain
 * member turn. Rides {@code GroupConversation.PendingHumanInput.detail}; a
 * {@code null} detail is a member's speaking turn (I6) or a facilitator
 * escalation, exactly as before this record existed.
 *
 * @param kind
 *            {@link #KIND_TIE_BREAK} or {@link #KIND_TASK}
 * @param options
 *            for a tie-break, the options the decider chooses among — the tied
 *            ones, or every option on a quorum failure. Empty otherwise
 * @param taskId
 *            for a human task, the task being worked on. {@code null} otherwise
 * @param transcriptFrom
 *            for a tie-break, the transcript index the VOTE repeat's entries
 *            start at, so the ballots can be re-read on resolution instead of
 *            being copied into the document
 * @param timeoutResolution
 *            for a tie-break, what a timeout resolves to
 *            ({@code NO_DECISION}/{@code MODERATOR_DECIDES}). Kept here, not in
 *            {@code PendingHumanInput.onTimeout}, which stays
 *            {@code SKIP_TURN}/{@code ABORT} so an older timeout handler never
 *            meets a value it does not know
 */
public record HumanRequestDetail(String kind, List<String> options, String taskId, Integer transcriptFrom,
        String timeoutResolution) {

    /** A tied vote waiting for its decider. */
    public static final String KIND_TIE_BREAK = "TIE_BREAK";
    /** A task-force task assigned to a HUMAN member. */
    public static final String KIND_TASK = "TASK";

    public HumanRequestDetail {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** A tie-break request. */
    public static HumanRequestDetail tieBreak(List<String> options, int transcriptFrom, String timeoutResolution) {
        return new HumanRequestDetail(KIND_TIE_BREAK, options, null, transcriptFrom, timeoutResolution);
    }

    @JsonIgnore
    public boolean isTieBreak() {
        return KIND_TIE_BREAK.equals(kind);
    }

    @JsonIgnore
    public boolean isTask() {
        return KIND_TASK.equals(kind);
    }
}
