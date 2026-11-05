/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

/**
 * {@code AgentConfiguration.hitlConfig.oversight}: post-hoc oversight of the
 * tool calls an agent makes <em>without</em> asking (a sampled audit) and the
 * policy by which a gated category may earn its way out of the gate (earned
 * autonomy).
 * <p>
 * Absent = the behaviour before 6.7: nothing is sampled, decisions are still
 * counted for analytics, nothing is ever recommended or suspended.
 *
 * <pre>{@code
 * "oversight": {
 *   "sampledAudit": { "rules": [ { "match": "http.post:/crm/notes", "rate": 0.1 } ] },
 *   "autonomyPolicy": { "categories": { "tool:http.post:/crm/notes": {} } }
 * }
 * }</pre>
 *
 * Lives on the agent document only, never on an LLM task: an {@code llmstore}
 * write can then never touch it, and the self-ungating guard protects it with
 * the rest of {@code hitlConfig}.
 *
 * @since 6.7.0
 */
public class OversightConfig {

    private SampledAuditConfig sampledAudit;
    private AutonomyPolicyConfig autonomyPolicy;

    public SampledAuditConfig getSampledAudit() {
        return sampledAudit;
    }

    public void setSampledAudit(SampledAuditConfig sampledAudit) {
        this.sampledAudit = sampledAudit;
    }

    public AutonomyPolicyConfig getAutonomyPolicy() {
        return autonomyPolicy;
    }

    public void setAutonomyPolicy(AutonomyPolicyConfig autonomyPolicy) {
        this.autonomyPolicy = autonomyPolicy;
    }
}
