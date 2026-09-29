/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Human decision on a paused conversation or group discussion.
 * Jackson-deserialized from the REST request body.
 */
public class HitlDecision {

    /**
     * Upper bound for the free-text reviewer note — the single source of truth for
     * every surface that accepts a decision body (regular resume, group approve,
     * channel integrations).
     */
    public static final int MAX_NOTE_LENGTH = 4096;

    public enum HitlVerdict {
        APPROVED, REJECTED;

        /**
         * Case-insensitive parsing on every surface — "approved" and "APPROVED" are the
         * same human intent; rejecting on casing is needless 400 noise. An unrecognized
         * value yields {@code null} (not a raw Jackson enum error), which every resume
         * surface reports as the friendly "must include a 'verdict'" 400 — so no
         * request-local deserializer is needed to soften the error.
         */
        @JsonCreator
        public static HitlVerdict fromString(String value) {
            if (value == null) {
                return null;
            }
            try {
                return HitlVerdict.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unrecognized) {
                return null;
            }
        }
    }

    private HitlVerdict verdict;
    private String note;
    /** userId — set server-side from SecurityIdentity (not trusted from body). */
    private String decidedBy;
    /**
     * Per-tool-call verdicts, keyed by {@code callId} — TOOL_CALL pauses only.
     * Calls not listed here inherit the top-level {@link #verdict}.
     */
    private Map<String, ToolCallDecision> toolDecisions;
    /**
     * Optional: the id of the pause this decision was made for, as reported by
     * {@code approval-status} ({@code pauseId}). When set, the decision applies
     * only while the conversation is still in THAT pause; if it has since been
     * resumed and paused again — a different request — the decision is refused with
     * a conflict instead of approving something the reviewer never saw. Omitted,
     * the decision applies to whatever pause is current (the pre-existing
     * behaviour).
     */
    private String pauseId;

    /**
     * The id of a pause that started at {@code pausedAt}: its epoch milliseconds.
     * <p>
     * Milliseconds because that is what survives a round trip through every store —
     * Mongo keeps a date to the millisecond — so an id computed from a freshly
     * paused in-memory bookmark and one computed from the stored document agree.
     * Every pause stamps a new {@code pausedAt}, so a resume followed by a re-pause
     * always yields a different id.
     *
     * @return the pause id, or {@code null} when there is no pause
     */
    public static String pauseIdOf(Instant pausedAt) {
        return pausedAt == null ? null : Long.toString(pausedAt.toEpochMilli());
    }

    /**
     * Whether this decision may apply to the pause that started at
     * {@code currentPausedAt}: always, when the decision names no pause; otherwise
     * only when the ids are equal.
     */
    public boolean appliesToPause(Instant currentPausedAt) {
        if (pauseId == null || pauseId.isBlank()) {
            return true;
        }
        return pauseId.trim().equals(pauseIdOf(currentPausedAt));
    }

    public HitlVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(HitlVerdict verdict) {
        this.verdict = verdict;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Map<String, ToolCallDecision> getToolDecisions() {
        return toolDecisions;
    }

    public void setToolDecisions(Map<String, ToolCallDecision> toolDecisions) {
        this.toolDecisions = toolDecisions;
    }

    public String getPauseId() {
        return pauseId;
    }

    public void setPauseId(String pauseId) {
        this.pauseId = pauseId;
    }
}
