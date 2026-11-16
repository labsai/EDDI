/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How Agent Health judges the conversations of one agent version — the
 * versioned part of the health configuration
 * ({@code AgentConfiguration#getHealthConfig()},
 * {@code docs/agent-health.md#conversation-outcomes}). It travels with the
 * agent in export, import and agent sync, and is read from the <em>version a
 * session ended on</em>, like {@code ratingConfig}: changing it in a new
 * version never re-judges sessions that ran on an older one.
 * <p>
 * Absent, every default applies: no action is mapped to an outcome, a session
 * that ended quietly after a successful last turn counts as
 * {@code ASSUMED_RESOLVED} from {@code silence}, the inactivity windows are the
 * deployment's ({@code eddi.health.session-window}, 24 h; e-mail
 * {@code eddi.health.email-session-window}, 72 h), and the
 * {@code report_conversation_outcome} tool is not offered.
 * <p>
 * Thresholds, presets and budgets are deliberately <em>not</em> here: tuning
 * them must not mint agent versions (they belong to the unversioned health
 * profile, H4). Save-time checks: {@code HealthConfigValidator}.
 *
 * @since 6.7.0
 */
public class HealthConfig {

    /** The outcomes an action, or the self-report tool, may declare. */
    public static final List<String> DECLARABLE_OUTCOMES = List.of("RESOLVED", "ESCALATED", "DEFLECTED", "FAILED", "ABANDONED");

    private Map<String, String> outcomes = new LinkedHashMap<>();
    private boolean assumeResolvedOnSilence = true;
    private Map<String, Integer> inactivityWindowHours = new LinkedHashMap<>();
    private boolean selfReportTool = false;

    /**
     * Action name → outcome ({@link #DECLARABLE_OUTCOMES}). A session in which the
     * agent emitted a mapped action ends with that outcome, evidence
     * {@code agent_action}; when several mapped actions were emitted, the last one
     * counts. {@code CONVERSATION_END} and {@code STOP_CONVERSATION} may be mapped
     * (they otherwise count as {@code ASSUMED_RESOLVED} from {@code agent_closed});
     * the other reserved actions may not.
     */
    public Map<String, String> getOutcomes() {
        return outcomes;
    }

    public void setOutcomes(Map<String, String> outcomes) {
        this.outcomes = outcomes == null ? new LinkedHashMap<>() : outcomes;
    }

    /**
     * Whether a session that went quiet after a successful last turn — no mapped
     * outcome, no question left open, no negative rating — counts as
     * {@code ASSUMED_RESOLVED} with evidence {@code silence}. Default {@code true};
     * {@code false} records {@code ENDED_QUIETLY} instead. Either way the
     * resolution rate shows how much of it rests on silence.
     */
    public boolean isAssumeResolvedOnSilence() {
        return assumeResolvedOnSilence;
    }

    public void setAssumeResolvedOnSilence(boolean assumeResolvedOnSilence) {
        this.assumeResolvedOnSilence = assumeResolvedOnSilence;
    }

    /**
     * Origin → hours of inactivity after which a session closes, overriding the
     * deployment's window for that origin ({@code api: 12}, {@code email: 96}). 1
     * to 720 hours. Absent origins keep the deployment default.
     */
    public Map<String, Integer> getInactivityWindowHours() {
        return inactivityWindowHours;
    }

    public void setInactivityWindowHours(Map<String, Integer> inactivityWindowHours) {
        this.inactivityWindowHours = inactivityWindowHours == null ? new LinkedHashMap<>() : inactivityWindowHours;
    }

    /**
     * Whether the {@code report_conversation_outcome} tool is offered to this
     * agent's LLM tasks (which must also have {@code enableBuiltInTools}, and name
     * {@code report_conversation_outcome} when they use a whitelist). The model
     * grading itself is weak evidence and is always shown apart
     * ({@code self_reported}). Default {@code false}.
     */
    public boolean isSelfReportTool() {
        return selfReportTool;
    }

    public void setSelfReportTool(boolean selfReportTool) {
        this.selfReportTool = selfReportTool;
    }
}
