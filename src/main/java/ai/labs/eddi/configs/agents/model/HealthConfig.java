/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import java.util.ArrayList;
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

    /**
     * The value of an {@link #getOutcomeEvents()} entry that declares a business
     * event type without an outcome: it is counted ({@code event:<type>}), but
     * never decides how a session ended.
     */
    public static final String NO_OUTCOME = "NONE";

    /** The outcomes an action, or the self-report tool, may declare. */
    public static final List<String> DECLARABLE_OUTCOMES = List.of("RESOLVED", "ESCALATED", "DEFLECTED", "FAILED", "ABANDONED");

    private Map<String, String> outcomes = new LinkedHashMap<>();
    private boolean assumeResolvedOnSilence = true;
    private Map<String, Integer> inactivityWindowHours = new LinkedHashMap<>();
    private boolean selfReportTool = false;
    private List<String> externalRefs = new ArrayList<>();
    private Map<String, String> outcomeEvents = new LinkedHashMap<>();
    private boolean hashRefs = false;

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

    /**
     * Property names whose values identify a business object the agent works on
     * ({@code orderId}, {@code ticketId}) — the references outcome events are
     * matched by ({@code docs/agent-health.md#business-outcomes}). When a turn ends
     * with one of them set (by {@code property.json}, a {@code postResponse}
     * instruction or the model), its value is recorded on the turn's health row —
     * plain, or salted-hashed with {@link #isHashRefs()}. At most 10 names; names
     * that look like personal identifiers ({@code email}, {@code phone},
     * {@code iban}, …) are refused unless {@code hashRefs} is on. Never a secret: a
     * {@code scope: secret} or vaulted property is not recorded.
     */
    public List<String> getExternalRefs() {
        return externalRefs;
    }

    public void setExternalRefs(List<String> externalRefs) {
        this.externalRefs = externalRefs == null ? new ArrayList<>() : externalRefs;
    }

    /**
     * Business event type → outcome ({@link #DECLARABLE_OUTCOMES}), or
     * {@link #NO_OUTCOME} to count the type without letting it decide an outcome:
     * {@code "order.placed": "RESOLVED"}, {@code "ticket.reopened": "FAILED"},
     * {@code "order.refunded": "NONE"}. A matched event of a mapped type decides
     * its session's outcome above every other source (evidence
     * {@code external_event} or {@code conversation_id_event}). Declared types are
     * the agent's business measures ({@code event:<type>}). Read from the version a
     * session ended on.
     */
    public Map<String, String> getOutcomeEvents() {
        return outcomeEvents;
    }

    public void setOutcomeEvents(Map<String, String> outcomeEvents) {
        this.outcomeEvents = outcomeEvents == null ? new LinkedHashMap<>() : outcomeEvents;
    }

    /**
     * Whether {@link #getExternalRefs()} values are stored and matched as salted
     * HMAC-SHA256 hashes (a per-deployment salt kept in the vault's metadata store)
     * instead of in plain. Default {@code false}: a plain reference is what an
     * export can join on. With hashing on, nothing in Agent Health holds the value
     * itself, and identifier-like reference names are allowed.
     */
    public boolean isHashRefs() {
        return hashRefs;
    }

    public void setHashRefs(boolean hashRefs) {
        this.hashRefs = hashRefs;
    }
}
