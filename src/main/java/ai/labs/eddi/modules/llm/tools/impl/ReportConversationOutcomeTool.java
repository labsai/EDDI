/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.health.HealthConfigPolicy;
import ai.labs.eddi.engine.health.HealthOutcomes;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.tools.ConversationBound;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;

/**
 * {@code report_conversation_outcome} — the agent's model says how the session
 * went, for Agent Health ({@code docs/agent-health.md#the-self-report-tool}).
 * <p>
 * <b>Opt-in, twice.</b> Offered only when the agent version enables it
 * ({@code healthConfig.selfReportTool}) and the LLM task has
 * {@code enableBuiltInTools} (naming {@code report_conversation_outcome} when
 * it uses a whitelist) — the shape of the user-memory tool's gate.
 * <p>
 * <b>Weak evidence, and labelled so.</b> The claim is stored on the current
 * step under {@link MemoryKeys#HEALTH_SELF_REPORTED_OUTCOME} and reaches the
 * turn row; the session's last claim counts as evidence {@code self_reported},
 * below a mapped action and never as <em>confirmed</em>. Calling it again in
 * the same turn replaces the claim (last write wins), so a model that calls it
 * in a loop changes one value, not a hundred.
 * <p>
 * <b>No text.</b> The tool takes the outcome only — no reason. A reason would
 * be text the model derived from the conversation, and Agent Health rows hold
 * no conversation content (they are kept for the turn-row retention and
 * exported under GDPR); the mapping of actions to outcomes is where a designer
 * says <em>why</em>.
 * <p>
 * A template bean: production calls copies {@link #boundTo bound} to the turn's
 * step; the unbound bean refuses every call.
 */
@ApplicationScoped
@ConversationBound
public class ReportConversationOutcomeTool {

    /** The whitelist key ({@code builtInToolsWhitelist}) that selects the tool. */
    public static final String WHITELIST_KEY = "report_conversation_outcome";

    @Inject
    HealthConfigPolicy healthConfigPolicy;

    /** The step this instance writes to; null on the unbound catalog bean. */
    private IWritableConversationStep step;

    public ReportConversationOutcomeTool() {
    }

    /** With its policy — for tests and direct construction. */
    public ReportConversationOutcomeTool(HealthConfigPolicy healthConfigPolicy) {
        this.healthConfigPolicy = healthConfigPolicy;
    }

    /**
     * Whether the conversation's agent version offers the tool — the agent-level
     * half of the gate.
     */
    public boolean enabledFor(IConversationMemory memory) {
        return memory != null && healthConfigPolicy != null
                && healthConfigPolicy.selfReportToolEnabled(memory.getAgentId(), memory.getAgentVersion());
    }

    /** A copy of this tool that writes to {@code memory}'s current step. */
    public ReportConversationOutcomeTool boundTo(IConversationMemory memory) {
        ReportConversationOutcomeTool bound = new ReportConversationOutcomeTool();
        bound.healthConfigPolicy = this.healthConfigPolicy;
        bound.step = memory == null ? null : memory.getCurrentStep();
        return bound;
    }

    @Tool(name = "report_conversation_outcome", value = "Report how this conversation went, once you know: RESOLVED (the person got what "
            + "they came for), ESCALATED (handed to a human), DEFLECTED (out of scope; pointed elsewhere) or FAILED (you could not help). "
            + "Call it at most once, when the outcome is clear; a later call replaces the earlier one. Do not mention it to the person.")
    public String reportConversationOutcome(@P("One of RESOLVED, ESCALATED, DEFLECTED, FAILED") String outcome) {
        if (step == null) {
            return "Error: the outcome can only be reported from within a conversation.";
        }
        String value = outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT);
        if (!HealthOutcomes.SELF_REPORTABLE.contains(value)) {
            return "Error: outcome must be one of " + String.join(", ", HealthOutcomes.SELF_REPORTABLE) + ".";
        }
        step.storeData(new Data<>(MemoryKeys.HEALTH_SELF_REPORTED_OUTCOME, value));
        return "Recorded: " + value + ".";
    }
}
