/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request object for creating an API Agent from an OpenAPI spec. Maps to the
 * MCP {@code create_api_agent} tool parameters and the REST
 * {@code POST /administration/agents/setup-api} endpoint body.
 *
 * @author ginccc
 */
public record CreateApiAgentRequest(@JsonProperty(required = true) String agentName, @JsonProperty(required = true) String systemPrompt,
        @JsonProperty(required = true) String openApiSpec, String provider, String model, String apiKey, String apiBaseUrl, String apiAuth,
        String endpoints, Boolean enableQuickReplies, Boolean enableSentimentAnalysis, Boolean deploy, String environment,
        /*
         * Base URL of the LLM provider itself (Ollama, Jlama). Named apart from
         * apiBaseUrl — the target server of the *generated tools* — because the two sit
         * side by side in this record and a caller cannot tell them apart otherwise.
         * Appended last so the positional constructor used by McpSetupTools stays
         * unambiguous.
         */
        String llmBaseUrl,
        /*
         * HITL configuration for the created agent, including the tool-approval gate
         * (hitlConfig.toolApprovals). Without this the wizard could only ever build a
         * bare AgentConfiguration, so EVERY agent it created had hitlConfig == null and
         * an inert gate — no caller could provision a gated agent through setup-api at
         * all.
         *
         * Deliberately NOT exposed on the MCP create_api_agent tool: that tool
         * provisions an agent with an arbitrary endpoint filter, so letting a model
         * also choose the gate would make it a complete escape from any allow-list.
         * McpSetupTools passes null.
         *
         * Appended last for the same positional-constructor reason as llmBaseUrl.
         */
        AgentConfiguration.HitlConfig hitlConfig,
        /*
         * Comma-separated MCP server URLs, as on the setup_agent path. An API agent
         * could previously only hold tools generated from the OpenAPI spec, so an agent
         * needing both — REST endpoints the spec describes AND an MCP server's tools —
         * was unreachable through this wizard and had to be assembled by hand.
         */
        String mcpServerUrls,
        /*
         * Tool-loop iteration budget for the generated LLM task
         * (LlmConfiguration.Task.maxToolIterations). Null keeps the engine default (10,
         * ToolLoopRunner). That default suits a conversational agent with a handful of
         * tools; an agent whose ENTIRE toolset is a spec's endpoints — the Platform
         * Operator above all — routinely needs a longer chain for one legitimate task
         * (an agent build via granular store endpoints is ~8 creates plus descriptor
         * patches plus verification reads), and at the default it dies mid-work on the
         * iteration cap.
         *
         * Bounded by AgentSetupService.MAX_TOOL_ITERATIONS: every iteration is an LLM
         * round-trip carrying the full tool context, so an absurd value is a cost and
         * latency hazard, not a capability.
         *
         * Appended last for the same positional-constructor reason as llmBaseUrl; the
         * MCP create_api_agent tool passes null.
         */
        Integer maxToolIterations) {
}
