package ai.labs.eddi.engine.setup;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request object for setting up a standard Agent. Maps to the MCP
 * {@code setup_agent} tool parameters and the REST
 * {@code POST /administration/agents/setup} endpoint body.
 *
 * @author ginccc
 */
public record SetupAgentRequest(@JsonProperty(required = true) String name, @JsonProperty(required = true) String systemPrompt, String provider,
        String model, String apiKey, String baseUrl, String introMessage, Boolean enableBuiltInTools, String builtInToolsWhitelist,
        Boolean enableQuickReplies, Boolean enableSentimentAnalysis, Boolean deploy, String environment) {
}
