package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.CreateApiAgentRequest;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP composite tool for setting up a fully working Agent in a single call.
 * Thin wrapper that delegates to {@link AgentSetupService}.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpSetupTools {

    private static final Logger LOGGER = Logger.getLogger(McpSetupTools.class);

    private final AgentSetupService agentSetupService;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public McpSetupTools(AgentSetupService agentSetupService, IJsonSerialization jsonSerialization) {
        this.agentSetupService = agentSetupService;
        this.jsonSerialization = jsonSerialization;
    }

    @Tool(name = "setup_agent", description = "Create a fully working, deployed Agent in a single call. "
            + "This creates all necessary resources (behavior rules, LLM connection, "
            + "output set, package, agent), names them, and optionally deploys the agent. "
            + "This is the fastest way to get a new Agent running — equivalent to the Agent Father workflow.")
    public String setupAgent(@ToolArg(description = "Agent name (required)") String name,
            @ToolArg(description = "System prompt / role for the LLM (required). "
                    + "Describes the agent's personality and purpose.") String systemPrompt,
            @ToolArg(description = "LLM provider type: 'anthropic' (default), 'openai', 'gemini', "
                    + "'gemini-vertex', 'huggingface', 'ollama', or 'jlama'") String provider,
            @ToolArg(description = "Model name, e.g. 'claude-sonnet-4-6' (default), 'gpt-5.4', "
                    + "'gemini-3.1-pro-preview', 'deepseek-chat', 'llama3.2:1b' (ollama)") String model,
            @ToolArg(description = "API key for the LLM provider. Required for cloud providers "
                    + "(anthropic, openai, gemini). Optional/unused for local LLMs (ollama, jlama). "
                    + "Can be a vault reference like '${vault:openai-key}'.") String apiKey,
            @ToolArg(description = "Base URL for the LLM provider (optional). "
                    + "Useful for ollama when running in Docker (e.g. 'http://host.docker.internal:11434')") String baseUrl,
            @ToolArg(description = "Greeting message shown when a conversation starts (optional)") String introMessage,
            @ToolArg(description = "Enable built-in tools like calculator, datetime, websearch? (default: false)") Boolean enableBuiltInTools,
            @ToolArg(description = "Comma-separated list of specific built-in tools to enable "
                    + "(e.g. 'calculator,datetime,websearch'). Only used if enableBuiltInTools is true.") String builtInToolsWhitelist,
            @ToolArg(description = "Enable quick reply buttons in Agent responses? (default: false). "
                    + "When enabled, the LLM returns structured JSON with quick reply suggestions. "
                    + "Note: streaming is not supported when this is enabled.") Boolean enableQuickReplies,
            @ToolArg(description = "Enable ad-hoc sentiment analysis in Agent responses? (default: false). "
                    + "When enabled, the LLM returns structured JSON with sentiment scores, "
                    + "emotion detection, intent classification, and urgency rating. "
                    + "Note: streaming is not supported when this is enabled.") Boolean enableSentimentAnalysis,
            @ToolArg(description = "Comma-separated MCP server URLs to connect to (optional). "
                    + "Each URL creates a McpCalls workflow extension that the agent auto-discovers. "
                    + "Example: 'http://localhost:7070/mcp, http://tools.example.com/mcp'") String mcpServerUrls,
            @ToolArg(description = "Automatically deploy the Agent after creation? (default: true)") Boolean deploy,
            @ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        try {
            var request = new SetupAgentRequest(name, systemPrompt, provider, model, apiKey, baseUrl, introMessage, enableBuiltInTools,
                    builtInToolsWhitelist, enableQuickReplies, enableSentimentAnalysis, mcpServerUrls, deploy, environment);
            var result = agentSetupService.setupAgent(request);
            return jsonSerialization.serialize(result);
        } catch (AgentSetupException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("MCP setup_agent failed", e);
            return errorJson("Failed to set up agent: " + e.getMessage());
        }
    }

    @Tool(name = "create_api_agent", description = "Create a Agent that can interact with any REST API using an OpenAPI spec. "
            + "This parses the OpenAPI spec, generates ApiCalls configurations for each tag group, "
            + "creates behavior rules with API-specific actions, and deploys a Agent that the LLM "
            + "can use to call the API endpoints. Endpoints are grouped by OpenAPI tag into separate "
            + "ApiCalls resources. Deprecated endpoints are automatically skipped.")
    public String createApIAgent(@ToolArg(description = "Agent name (required)") String name,
            @ToolArg(description = "System prompt for the LLM (required). " + "Include instructions on how to use the API.") String systemPrompt,
            @ToolArg(description = "OpenAPI 3.x spec as JSON/YAML string or a URL (required)") String openApiSpec,
            @ToolArg(description = "LLM provider: 'anthropic' (default), 'openai', 'gemini', etc.") String provider,
            @ToolArg(description = "Model name (default: 'claude-sonnet-4-6')") String model,
            @ToolArg(description = "LLM API key (required for cloud providers). " + "Use vault reference: '${vault:key-name}'.") String apiKey,
            @ToolArg(description = "Override the API base URL from the spec (optional)") String apiBaseUrl,
            @ToolArg(description = "Authorization header for API calls, e.g. 'Bearer token123' (optional). "
                    + "Use vault reference: '${vault:api-token}'.") String apiAuth,
            @ToolArg(description = "Comma-separated endpoint filter, e.g. 'GET /users,POST /orders'. "
                    + "If omitted, all non-deprecated endpoints are included.") String endpoints,
            @ToolArg(description = "Enable quick reply buttons in Agent responses? (default: false)") Boolean enableQuickReplies,
            @ToolArg(description = "Enable sentiment analysis in Agent responses? (default: false)") Boolean enableSentimentAnalysis,
            @ToolArg(description = "Deploy after creation? (default: true)") Boolean deploy,
            @ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        try {
            var request = new CreateApiAgentRequest(name, systemPrompt, openApiSpec, provider, model, apiKey, apiBaseUrl, apiAuth, endpoints,
                    enableQuickReplies, enableSentimentAnalysis, deploy, environment);
            var result = agentSetupService.createApiAgent(request);
            return jsonSerialization.serialize(result);
        } catch (AgentSetupException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("MCP create_api_agent failed", e);
            return errorJson("Failed to create API agent: " + e.getMessage());
        }
    }

    // ==================== Static Delegates for Test Compatibility
    // ====================

    /**
     * @see AgentSetupService#buildPromptResponseJson(boolean, boolean)
     */
    public static String buildPromptResponseJson(boolean quickReplies, boolean sentiment) {
        return AgentSetupService.buildPromptResponseJson(quickReplies, sentiment);
    }

    /**
     * @see AgentSetupService#supportsResponseFormat(String)
     */
    public static boolean supportsResponseFormat(String modelType) {
        return AgentSetupService.supportsResponseFormat(modelType);
    }

    /**
     * @see AgentSetupService#isLocalLlmProvider(String)
     */
    public static boolean isLocalLlmProvider(String provider) {
        return AgentSetupService.isLocalLlmProvider(provider);
    }

    // ==================== Service Accessors for Tests ====================

    /**
     * Provide access to the underlying service for tests that need to call config
     * builder methods directly.
     */
    AgentSetupService getService() {
        return agentSetupService;
    }
}
