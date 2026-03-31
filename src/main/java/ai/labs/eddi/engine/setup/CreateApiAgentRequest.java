package ai.labs.eddi.engine.setup;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request object for creating an API Agent from an OpenAPI spec. Maps to the
 * MCP {@code create_api_agent} tool parameters and the REST
 * {@code POST /administration/agents/setup-api} endpoint body.
 *
 * @author ginccc
 */
public record CreateApiAgentRequest(@JsonProperty(required = true) String name, @JsonProperty(required = true) String systemPrompt,
        @JsonProperty(required = true) String openApiSpec, String provider, String model, String apiKey, String apiBaseUrl, String apiAuth,
        String endpoints, Boolean enableQuickReplies, Boolean enableSentimentAnalysis, Boolean deploy, String environment) {
}
