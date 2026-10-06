/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request object for setting up a standard Agent. Maps to the MCP
 * {@code setup_agent} tool parameters and the REST
 * {@code POST /administration/agents/setup} endpoint body.
 *
 * @author ginccc
 */
public record SetupAgentRequest(@JsonProperty(required = true)
@JsonAlias("name") String agentName, @JsonProperty(required = true) String systemPrompt, String provider,
        String model, String apiKey, String baseUrl, String introMessage, Boolean enableBuiltInTools, String builtInToolsWhitelist,
        Boolean enableQuickReplies, Boolean enableSentimentAnalysis, String mcpServerUrls, Boolean deploy, String environment,
        /*
         * HITL configuration for the created agent — the exact counterpart of
         * CreateApiAgentRequest.hitlConfig, added for the identical reason: without it
         * this path could only ever build a bare AgentConfiguration, so every standard
         * agent it created had hitlConfig == null and an inert gate.
         *
         * Deliberately NOT exposed on the MCP setup_agent tool, for the same reason
         * create_api_agent's is not: this path already lets the caller choose the
         * created agent's own tool surface (enableBuiltInTools, builtInToolsWhitelist,
         * mcpServerUrls), so also letting it choose that agent's gate would let a
         * caller build an ungated agent at will. McpSetupTools and CreateSubAgentTool
         * both pass null.
         *
         * Appended last, matching CreateApiAgentRequest's own convention — every
         * positional-constructor call site adds new fields at the end so existing
         * argument positions never shift.
         */
        AgentConfiguration.HitlConfig hitlConfig,
        /*
         * Name of the vault entry the LLM API key lives under, so several agents can be
         * provisioned against ONE stored credential.
         *
         * Without it the only way to share a key was to paste the previous agent's
         * ${vault:...} reference into apiKey — which works, but only if you can find
         * the reference, and the generated names (setup.<agent>.<timestamp>.apiKey) are
         * neither guessable nor meaningful. Naming the entry makes the shared key a
         * deliberate, stable choice: "${vault:openai-prod}", set once, reused by every
         * agent that should rotate together.
         *
         * Accepts a bare name ("openai-prod") or the full reference form
         * ("${vault:openai-prod}"). Combined with apiKey it CREATES the entry under
         * that name; alone it REQUIRES the entry to already exist. It never overwrites
         * an entry that holds a different value — see
         * AgentSetupService.useNamedVaultKey.
         *
         * Appended last, matching this record's convention: every positional
         * constructor call site adds new fields at the end so existing argument
         * positions never shift.
         */
        String vaultKeyName,
        /*
         * Admin opt-in: after creating the agent and BEFORE deploying it, add the new
         * agent's id to the grant of every RESTRICTED vault secret it references. A
         * grant lists agent ids, so without this every new agent using a restricted
         * secret fails its first deploy by construction. Each grant change is audited;
         * a secret open to every agent ("*") is never touched.
         *
         * Refused for any caller who is not eddi-admin. Deliberately NOT exposed on any
         * MCP tool: an LLM that can grant itself secrets defeats the gate. In-process
         * callers (McpSetupTools, CreateSubAgentTool) use the overload below, which
         * passes null.
         */
        Boolean grantReferencedSecrets) {

    /** Every field but {@link #grantReferencedSecrets}, which stays off. */
    public SetupAgentRequest(String agentName, String systemPrompt, String provider, String model, String apiKey, String baseUrl,
            String introMessage, Boolean enableBuiltInTools, String builtInToolsWhitelist, Boolean enableQuickReplies,
            Boolean enableSentimentAnalysis, String mcpServerUrls, Boolean deploy, String environment, AgentConfiguration.HitlConfig hitlConfig,
            String vaultKeyName) {
        this(agentName, systemPrompt, provider, model, apiKey, baseUrl, introMessage, enableBuiltInTools, builtInToolsWhitelist, enableQuickReplies,
                enableSentimentAnalysis, mcpServerUrls, deploy, environment, hitlConfig, vaultKeyName, null);
    }
}
