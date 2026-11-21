/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretMetadata;

import java.util.List;

/**
 * On whose behalf a {@code ${vault:…}} reference is resolved: the identity a
 * secret's agent grant ({@code allowedAgents}) is checked against when
 * {@link SecretResolver} resolves it.
 * <p>
 * <b>Why every resolution names one.</b> The grant used to be checked only when
 * an agent was deployed. On a cluster that left two gaps (#1179): a deployment
 * that passed the gate on another node after a secret's grant was written or
 * narrowed, and an {@code autoDeploy=false} deployment on another node, which
 * no shared record names. Both resolved the secret, from the vault or from the
 * resolver's cache, without the grant ever being read for them. Checking where
 * the value is handed out closes both, whatever any node saw or did not see.
 * The resolver has no overload without an access, so every call site had to
 * decide which of the three kinds below it is, and the compiler keeps it that
 * way.
 * <p>
 * <b>The three kinds.</b>
 * <ul>
 * <li>{@link #agent} — resolved for an agent's own configuration (LLM, API
 * call, MCP, A2A, RAG, connection credentials, Dream). The grant must name the
 * agent or be open to every agent. An agent access without an id is not an
 * exemption: it is treated as an identity that no restricted grant names, so it
 * only resolves secrets open to every agent — fail closed.</li>
 * <li>{@link #withoutAgent} — resolved for something that is not an agent but
 * runs configuration an editor wrote (a group-level model, a knowledge base's
 * ingestion). There is no identity to test, so the rule is the strictest one
 * that needs none, the one {@code ServiceJudgeVaultGrants} already applies: the
 * grant must be open to every agent.</li>
 * <li>{@link #outsideAgentGrants} — resolved for deployment infrastructure that
 * the agent grant has never governed and the deploy gate has never checked
 * (outbound proxy credentials, alert delivery, channel integrations). The grant
 * is not consulted. Each use names its purpose, and each is listed and argued
 * in {@code docs/secrets-vault.md}; adding one is a decision, not a
 * default.</li>
 * </ul>
 *
 * @param kind
 *            which rule applies
 * @param agentId
 *            the agent, for {@link Kind#AGENT}; {@code null} otherwise (and for
 *            an agent whose id is unknown)
 * @param purpose
 *            what the value is resolved for, for messages and logs; never a
 *            secret
 */
public record SecretAccess(Kind kind, String agentId, String purpose) {

    /** See the class comment. */
    public enum Kind {
        AGENT, WITHOUT_AGENT, OUTSIDE_AGENT_GRANTS
    }

    public SecretAccess {
        if (kind == null) {
            throw new IllegalArgumentException("A secret access needs a kind");
        }
        agentId = agentId == null || agentId.isBlank() ? null : agentId;
    }

    /**
     * Resolved for agent {@code agentId}'s configuration. A null or blank id is
     * accepted and fails closed: only secrets open to every agent resolve.
     */
    public static SecretAccess agent(String agentId) {
        return new SecretAccess(Kind.AGENT, agentId, null);
    }

    /**
     * Resolved for editor-written configuration that is not an agent's. Only
     * secrets open to every agent resolve.
     *
     * @param purpose
     *            e.g. {@code "knowledge base ingestion"}
     */
    public static SecretAccess withoutAgent(String purpose) {
        return new SecretAccess(Kind.WITHOUT_AGENT, null, purpose);
    }

    /**
     * Resolved for deployment infrastructure the agent grant does not govern. The
     * grant is not read.
     *
     * @param purpose
     *            e.g. {@code "outbound proxy credentials"}
     */
    public static SecretAccess outsideAgentGrants(String purpose) {
        return new SecretAccess(Kind.OUTSIDE_AGENT_GRANTS, null, purpose);
    }

    /** Whether the secret's agent grant is consulted at all. */
    public boolean checksGrant() {
        return kind != Kind.OUTSIDE_AGENT_GRANTS;
    }

    /**
     * Whether a secret granted to {@code allowedAgents} may be resolved for this
     * access. {@code null} and empty grants are open to every agent, as everywhere
     * else ({@link SecretMetadata#grantsAllAgents}).
     */
    public boolean permits(List<String> allowedAgents) {
        if (!checksGrant() || SecretMetadata.grantsAllAgents(allowedAgents)) {
            return true;
        }
        return kind == Kind.AGENT && agentId != null && allowedAgents.contains(agentId);
    }

    /** Who is asking, for a message: never a secret. */
    public String describe() {
        return switch (kind) {
            case AGENT -> agentId != null ? "agent '" + agentId + "'" : "an agent whose id is not known here";
            case WITHOUT_AGENT -> (purpose != null ? purpose : "a caller") + ", which is not an agent";
            case OUTSIDE_AGENT_GRANTS -> purpose != null ? purpose : "deployment infrastructure";
        };
    }
}
