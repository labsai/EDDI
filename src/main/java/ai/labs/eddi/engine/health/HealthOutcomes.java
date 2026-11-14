/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import java.util.List;
import java.util.Set;

/**
 * The vocabulary of conversation outcomes (plan §4.4): what a session ended
 * with, and the evidence that says so. Every outcome row records both, so a
 * rate is never more confident than its evidence.
 */
public final class HealthOutcomes {

    public static final String RESOLVED = "RESOLVED";
    public static final String ASSUMED_RESOLVED = "ASSUMED_RESOLVED";
    public static final String ESCALATED = "ESCALATED";
    public static final String ABANDONED = "ABANDONED";
    public static final String FAILED = "FAILED";
    public static final String DEFLECTED = "DEFLECTED";
    public static final String ENDED_QUIETLY = "ENDED_QUIETLY";
    public static final String OPEN = "OPEN";

    /** Every outcome. */
    public static final List<String> ALL = List.of(RESOLVED, ASSUMED_RESOLVED, ESCALATED, ABANDONED, FAILED, DEFLECTED, ENDED_QUIETLY, OPEN);

    /** What the model may claim with {@code report_conversation_outcome}. */
    public static final List<String> SELF_REPORTABLE = List.of(RESOLVED, ESCALATED, DEFLECTED, FAILED);

    /** An action of the agent mapped in {@code healthConfig.outcomes}. */
    public static final String EVIDENCE_AGENT_ACTION = "agent_action";
    /** The model's own claim ({@code report_conversation_outcome}) — weak. */
    public static final String EVIDENCE_SELF_REPORTED = "self_reported";
    /**
     * {@code CONVERSATION_END} / {@code STOP_CONVERSATION} after a successful turn.
     */
    public static final String EVIDENCE_AGENT_CLOSED = "agent_closed";
    /** The person went quiet after a successful last turn. */
    public static final String EVIDENCE_SILENCE = "silence";
    /** The session's last turn failed and nothing followed. */
    public static final String EVIDENCE_STRUCTURAL = "structural";
    /**
     * Read from the shape of the session after its window: a question left open, …
     */
    public static final String EVIDENCE_INFERRED = "inferred";
    /** Reserved for H5 (outcome events matched by reference). */
    public static final String EVIDENCE_EXTERNAL_EVENT = "external_event";
    /** Reserved for H5 (outcome events matched by conversation id). */
    public static final String EVIDENCE_CONVERSATION_ID_EVENT = "conversation_id_event";
    /** Reserved for H6 (a sampled judge). */
    public static final String EVIDENCE_JUDGED = "judged";

    /**
     * Every evidence value the contract names, including those later phases write.
     */
    public static final List<String> ALL_EVIDENCE = List.of(EVIDENCE_EXTERNAL_EVENT, EVIDENCE_CONVERSATION_ID_EVENT, EVIDENCE_AGENT_ACTION,
            EVIDENCE_AGENT_CLOSED, EVIDENCE_SILENCE, EVIDENCE_SELF_REPORTED, EVIDENCE_STRUCTURAL, EVIDENCE_INFERRED, EVIDENCE_JUDGED);

    /** Evidence that counts as <em>confirmed</em> in the resolution breakdown. */
    public static final Set<String> CONFIRMED_EVIDENCE = Set.of(EVIDENCE_EXTERNAL_EVENT, EVIDENCE_CONVERSATION_ID_EVENT, EVIDENCE_AGENT_ACTION);

    private HealthOutcomes() {
    }

    /** Whether an outcome counts as resolved (confirmed or assumed). */
    public static boolean resolved(String outcome) {
        return RESOLVED.equals(outcome) || ASSUMED_RESOLVED.equals(outcome);
    }
}
