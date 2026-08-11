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
        AgentConfiguration.HitlConfig hitlConfig) {
}
