/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

/**
 * The outcome of {@link TeamHandoffService#file}.
 *
 * @param outcome
 *            what happened
 * @param handoffId
 *            the handoff id for a filed or already-known handoff, else
 *            {@code null}
 * @param status
 *            the existing task's status for a {@link Outcome#DUPLICATE}, else
 *            {@code null}
 * @param message
 *            one sentence for the model (or the log): what happened and, for a
 *            refusal, why, in terms that do not invite a blind retry
 */
public record HandoffResult(Outcome outcome, String handoffId, String status, String message) {

    /** Every way a handoff attempt can end. */
    public enum Outcome {
        /** AUTO mode: a PENDING task is on the target's backlog. */
        FILED("filed", true),
        /** REVIEW mode: the item waits in the target's intake inbox. */
        QUEUED_FOR_REVIEW("filed", true),
        /** Already handed off earlier; nothing new was filed. An idempotent success. */
        DUPLICATE("duplicate", true),
        /** The target does not accept this source (or does not exist). */
        NOT_ACCEPTED("not_accepted", false),
        /** Self-handoff, a cycle, or the hop limit. */
        LOOP("loop", false),
        /** The source's per-run cap is reached. */
        RUN_CAPPED("capped", false),
        /** A per-day cap (this edge or the whole intake) is reached. */
        DAY_CAPPED("capped", false),
        /** The target's inbox is full. */
        INBOX_FULL("inbox_full", false),
        /** The target's backlog is full. */
        BACKLOG_FULL("backlog_full", false),
        /** A human on the target declined this recently. */
        DECLINED_RECENTLY("declined", false),
        /** The text was empty or over a limit. */
        INVALID("invalid", false),
        /** An admission screen refused it. */
        REFUSED_ADMISSION("refused_admission", false),
        /** A store could not answer; the safe reading is to refuse. */
        UNAVAILABLE("unavailable", false),
        /** Concurrent writers kept winning the compare-and-set. */
        CONFLICT("conflict", false);

        private final String metricTag;
        private final boolean success;

        Outcome(String metricTag, boolean success) {
            this.metricTag = metricTag;
            this.success = success;
        }

        /** The {@code outcome} tag of {@code eddi_team_handoff_total}. */
        public String metricTag() {
            return metricTag;
        }

        /** Whether the filer can treat the work as handed over. */
        public boolean success() {
            return success;
        }
    }

    /** Whether the work is handed over (filed, queued, or already there). */
    public boolean success() {
        return outcome.success();
    }

    static HandoffResult of(Outcome outcome, String message) {
        return new HandoffResult(outcome, null, null, message);
    }
}
