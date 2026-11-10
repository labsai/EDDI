/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.AutonomyPolicyConfig;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared builders for the oversight tests. */
public final class OversightFixtures {

    public static final String AGENT = "agent-1";
    public static final String CATEGORY = "tool:http.post:/crm/notes";
    public static final String PATTERN = "http.post:/crm/notes";
    public static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    private OversightFixtures() {
    }

    /** A decision by a verified human on the Manager. */
    public static ApprovalDecision decision(String outcome, Instant at) {
        return decision(outcome, at, "alice", DeciderKind.HUMAN_VERIFIED, "MANAGER", null);
    }

    public static ApprovalDecision decision(String outcome, Instant at, String decidedBy, DeciderKind kind, String channel, String reasonCode) {
        return new ApprovalDecision("d-" + at.toEpochMilli() + "-" + outcome + "#0", "d", 0, "regular", "TOOL_CALL", "TOOL_CALL", "call-1",
                AGENT, 3, null, "production", "conv-1", "user-1", CATEGORY, "DERIVED", outcome, reasonCode, null, null, decidedBy, kind, channel,
                at.minusSeconds(60), at, 60_000L, false, "createNote", false);
    }

    /**
     * {@code n} decisions, the first {@code approved} APPROVED and the rest of the
     * given outcome, one minute apart.
     */
    public static List<ApprovalDecision> decisions(int approved, int others, String otherOutcome) {
        List<ApprovalDecision> rows = new ArrayList<>();
        for (int i = 0; i < approved + others; i++) {
            rows.add(decision(i < approved ? "APPROVED" : otherOutcome, NOW.minusSeconds(3600L + i * 60L)));
        }
        return rows;
    }

    /**
     * An agent-level hitlConfig gating http.post:* with the category in the policy.
     */
    public static AgentConfiguration.HitlConfig gatedHitl(AutonomyPolicyConfig.CategoryPolicy policy) {
        var toolApprovals = new ToolApprovalsConfig();
        toolApprovals.setRequireApproval(List.of("http.post:*"));
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setToolApprovals(toolApprovals);
        var autonomy = new AutonomyPolicyConfig();
        autonomy.setCategories(Map.of(CATEGORY, policy != null ? policy : new AutonomyPolicyConfig.CategoryPolicy()));
        var oversight = new OversightConfig();
        oversight.setAutonomyPolicy(autonomy);
        hitl.setOversight(oversight);
        return hitl;
    }

    /** A sampled-audit config with one rule. */
    public static SampledAuditConfig audit(String match, double rate) {
        var rule = new SampledAuditConfig.Rule();
        rule.setMatch(match);
        rule.setRate(rate);
        var audit = new SampledAuditConfig();
        audit.setRules(new ArrayList<>(List.of(rule)));
        return audit;
    }

    /** A pending sample. */
    public static AuditSample sample(String id) {
        return new AuditSample(id, AGENT, 3, "conv-1", "user-1", "call-1", "createNote", "http", "post:/crm/notes", CATEGORY, PATTERN,
                PATTERN, "{\"text\":\"hi\"}", "abc", null, NOW, NOW.plusSeconds(86_400), AuditSample.Status.PENDING, null, null, null, null,
                1L);
    }
}
