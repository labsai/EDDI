/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

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
        String llmBaseUrl) {
}
