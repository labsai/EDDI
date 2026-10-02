/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.CreateApiAgentRequest;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP composite tool for setting up a fully working Agent in a single call.
 * Thin wrapper that delegates to {@link AgentSetupService}.
 *
 * @author ginccc
 */
@ApplicationScoped
@McpErrorResults
public class McpSetupTools {

    private static final Logger LOGGER = Logger.getLogger(McpSetupTools.class);

    private final AgentSetupService agentSetupService;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpSetupTools(AgentSetupService agentSetupService, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.agentSetupService = agentSetupService;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "setup_agent", description = "Create a fully working, deployed Agent in a single call. "
            + "This creates all necessary resources (behavior rules, LLM connection, "
            + "output set, package, agent), names them, and optionally deploys the agent. "
            + "This is the fastest way to get a new Agent running.")
    public String setupAgent(@ToolArg(description = "Agent name (required)") String agentName,
                             @ToolArg(description = "System prompt / role for the LLM (required). "
                                     + "Describes the agent's personality and purpose.") String systemPrompt,
                             @ToolArg(description = "LLM provider type: 'anthropic' (default), 'openai', 'gemini', 'gemini-vertex', 'huggingface', 'ollama', 'jlama', 'mistral', 'azure-openai', 'bedrock', 'oracle-genai', or an OpenAI-compatible provider: 'xai', 'deepseek', 'moonshot' (Kimi), 'qwen', 'zhipu' (Z.ai GLM), 'minimax', 'openrouter' or 'groq'",
                                      required = false, defaultValue = "anthropic") String provider,
                             @ToolArg(description = "Model name (optional; default: 'claude-sonnet-4-6' for anthropic, named OpenAI-compatible providers use their own default model), e.g. 'gpt-5.4', 'gemini-3.1-pro-preview', 'deepseek-flash', 'llama3.2:1b' (ollama)",
                                      required = false) String model,
                             @ToolArg(description = "API key for the LLM provider (optional, but needed by most cloud providers: anthropic, openai, gemini, mistral). Not needed for bedrock (uses IAM), oracle-genai (uses OCI auth), or local LLMs (ollama, jlama). Can be a vault reference like '${vault:openai-key}'. To put several agents on ONE key, pass the apiKeyVaultReference returned by an earlier setup_agent call here — that always reuses the named entry. A plaintext key the vault already holds is reused too, but only when the deployment leaves eddi.setup.vault-key-reuse at 'checksum' and the existing entry is granted to all agents; otherwise it is stored as a new entry.",
                                      required = false) String apiKey,
                             @ToolArg(description = "Base URL for the LLM provider (optional). Useful for ollama when running in Docker (e.g. 'http://host.docker.internal:11434'). For China-mainland endpoints of qwen/moonshot/zhipu/minimax pass that region's URL.",
                                      required = false) String baseUrl,
                             @ToolArg(description = "Greeting message shown when a conversation starts (optional)",
                                      required = false) String introMessage,
                             @ToolArg(description = "Enable built-in tools like calculator, datetime, websearch? (default: false)", required = false,
                                      defaultValue = "false") Boolean enableBuiltInTools,
                             @ToolArg(description = "Comma-separated list of specific built-in tools to enable (optional), e.g. 'calculator,datetime,websearch'. Only used if enableBuiltInTools is true.",
                                      required = false) String builtInToolsWhitelist,
                             @ToolArg(description = "Enable quick reply buttons in Agent responses? (default: false). When enabled, the LLM returns structured JSON with quick reply suggestions. Note: streaming is not supported when this is enabled.",
                                      required = false, defaultValue = "false") Boolean enableQuickReplies,
                             @ToolArg(description = "Enable ad-hoc sentiment analysis in Agent responses? (default: false). When enabled, the LLM returns structured JSON with sentiment scores, emotion detection, intent classification, and urgency rating. Note: streaming is not supported when this is enabled.",
                                      required = false, defaultValue = "false") Boolean enableSentimentAnalysis,
                             @ToolArg(description = "Comma-separated MCP server URLs to connect to (optional). Each URL creates a McpCalls workflow extension that the agent auto-discovers. Example: 'http://localhost:7070/mcp, http://tools.example.com/mcp'",
                                      required = false) String mcpServerUrls,
                             @ToolArg(description = "Automatically deploy the Agent after creation? (default: true)", required = false,
                                      defaultValue = "true") Boolean deploy,
                             @ToolArg(description = "Environment: 'production' (default) or 'test'", required = false,
                                      defaultValue = "production") String environment) {
        // Admin-only, exactly like REST /administration/agents/setup: provisioning
        // writes the caller's API key into the vault and picks the new agent's tools.
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            // hitlConfig is deliberately null and has no @ToolArg: this tool already
            // lets the caller choose the created agent's own tool surface
            // (enableBuiltInTools, builtInToolsWhitelist, mcpServerUrls), so also
            // letting it choose that agent's gate would let a caller build an
            // ungated agent at will. Provisioning a gated agent goes through the
            // REST setup endpoint.
            var request = new SetupAgentRequest(agentName, systemPrompt, provider, model, apiKey, baseUrl, introMessage, enableBuiltInTools,
                    builtInToolsWhitelist, enableQuickReplies, enableSentimentAnalysis, mcpServerUrls, deploy, environment, null,
                    // vaultKeyName is not exposed here. Not for reuse — apiKey already
                    // accepts a ${vault:...} reference (see its description), so an
                    // MCP caller can put an agent on an existing key today, and a
                    // plaintext apiKey de-duplicates by checksum wherever the
                    // deployment has that enabled. What vaultKeyName adds
                    // is choosing the NAME of a newly created entry and a
                    // value-must-match check on an existing one. Neither is needed to
                    // provision an agent, and neither belongs on a tool surface driven
                    // by a model: name-squatting an entry an operator intends to create,
                    // and a per-request "does key X hold value V" oracle.
                    null);
            var result = agentSetupService.setupAgent(request);
            return jsonSerialization.serialize(result);
        } catch (AgentSetupException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("MCP setup_agent failed", e);
            return errorJson("Failed to set up agent", e);
        }
    }

    @Tool(name = "create_api_agent", description = "Create a Agent that can interact with any REST API using an OpenAPI spec. "
            + "This parses the OpenAPI spec, generates ApiCalls configurations for each tag group, "
            + "creates behavior rules with API-specific actions, and deploys a Agent that the LLM "
            + "can use to call the API endpoints. Endpoints are grouped by OpenAPI tag into separate "
            + "ApiCalls resources. Deprecated endpoints are automatically skipped.")
    public String createApIAgent(@ToolArg(description = "Agent name (required)") String agentName,
                                 @ToolArg(description = "System prompt for the LLM (required). "
                                         + "Include instructions on how to use the API.") String systemPrompt,
                                 @ToolArg(description = "OpenAPI 3.x spec as JSON/YAML string or a URL (required)") String openApiSpec,
                                 @ToolArg(description = "LLM provider (default: 'anthropic'): 'anthropic', 'openai', 'gemini', 'mistral', 'azure-openai', 'bedrock', 'oracle-genai', 'xai', 'deepseek', 'moonshot', 'qwen', 'zhipu', 'minimax', 'openrouter', 'groq', etc.",
                                          required = false, defaultValue = "anthropic") String provider,
                                 @ToolArg(description = "Model name (optional; default: 'claude-sonnet-4-6' for anthropic, named OpenAI-compatible providers use their own default model)",
                                          required = false) String model,
                                 @ToolArg(description = "LLM API key (optional, but needed by most cloud providers: anthropic, openai, gemini, mistral). Not needed for bedrock (IAM) or oracle-genai (OCI auth). Use vault reference: '${vault:key-name}', e.g. the apiKeyVaultReference returned by an earlier setup call, to share one key across agents.",
                                          required = false) String apiKey,
                                 @ToolArg(description = "Override the API base URL from the spec (optional)", required = false) String apiBaseUrl,
                                 @ToolArg(description = "Authorization header for API calls, e.g. 'Bearer token123' (optional). Use vault reference: '${vault:api-token}'.",
                                          required = false) String apiAuth,
                                 @ToolArg(description = "Comma-separated endpoint filter, e.g. 'GET /users,POST /orders' (optional). If omitted, all non-deprecated endpoints are included.",
                                          required = false) String endpoints,
                                 @ToolArg(description = "Enable quick reply buttons in Agent responses? (default: false)", required = false,
                                          defaultValue = "false") Boolean enableQuickReplies,
                                 @ToolArg(description = "Enable sentiment analysis in Agent responses? (default: false)", required = false,
                                          defaultValue = "false") Boolean enableSentimentAnalysis,
                                 @ToolArg(description = "Deploy after creation? (default: true)", required = false,
                                          defaultValue = "true") Boolean deploy,
                                 @ToolArg(description = "Environment: 'production' (default) or 'test'", required = false,
                                          defaultValue = "production") String environment,
                                 @ToolArg(description = "Base URL of the LLM provider itself, for local models (optional), e.g. 'http://localhost:11434' for Ollama. Not the API's base URL — that is apiBaseUrl.",
                                          required = false) String llmBaseUrl,
                                 @ToolArg(description = "Comma-separated MCP server URLs whose tools the agent should also get, alongside the ones generated from the OpenAPI spec (optional).",
                                          required = false) String mcpServerUrls) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            // hitlConfig is deliberately null and has no @ToolArg: this tool already
            // provisions an agent with a caller-chosen endpoint filter, so also letting
            // the caller choose the approval gate would turn it into a complete escape
            // from whatever allow-list governs the agent doing the calling. Provisioning
            // a gated agent goes through the REST setup-api endpoint.
            // Trailing null: maxToolIterations is not exposed on this MCP tool either —
            // a model provisioning an agent must not raise its own iteration budget.
            var request = new CreateApiAgentRequest(agentName, systemPrompt, openApiSpec, provider, model, apiKey, apiBaseUrl, apiAuth, endpoints,
                    enableQuickReplies, enableSentimentAnalysis, deploy, environment, llmBaseUrl, null, mcpServerUrls, null,
                    null, // vaultKeyName — withheld for the reason given on setup_agent
                    null); // apiAuthHeader — withheld for the reason given on the record
            var result = agentSetupService.createApiAgent(request);
            return jsonSerialization.serialize(result);
        } catch (AgentSetupException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("MCP create_api_agent failed", e);
            return errorJson("Failed to create API agent", e);
        }
    }
}
