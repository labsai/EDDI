package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.behavior.IRestBehaviorStore;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorGroupConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConditionConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConfiguration;
import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.http.IRestHttpCallsStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.langchain.IRestLangChainStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP composite tool for setting up a fully working bot in a single call.
 * Codifies the Bot Father's 12-step pipeline as a programmatic Java operation.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpSetupTools {

    private static final Logger LOGGER = Logger.getLogger(McpSetupTools.class);

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestBotAdministration botAdmin;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public McpSetupTools(IRestInterfaceFactory restInterfaceFactory,
            IRestBotAdministration botAdmin,
            IJsonSerialization jsonSerialization) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.botAdmin = botAdmin;
        this.jsonSerialization = jsonSerialization;
    }

    @Tool(name = "setup_bot", description = "Create a fully working, deployed bot in a single call. " +
            "This creates all necessary resources (behavior rules, LLM connection, " +
            "output set, package, bot), names them, and optionally deploys the bot. " +
            "This is the fastest way to get a new bot running — equivalent to the Bot Father workflow.")
    public String setupBot(
            @ToolArg(description = "Bot name (required)") String name,
            @ToolArg(description = "System prompt / role for the LLM (required). " +
                    "Describes the bot's personality and purpose.") String systemPrompt,
            @ToolArg(description = "LLM provider type: 'anthropic' (default), 'openai', 'gemini', " +
                    "'gemini-vertex', 'huggingface', 'ollama', or 'jlama'") String model,
            @ToolArg(description = "Model name, e.g. 'claude-sonnet-4-6' (default), 'gpt-5.4', " +
                    "'gemini-3.1-pro-preview', 'deepseek-chat', 'llama3.2:1b' (ollama)") String modelName,
            @ToolArg(description = "API key for the LLM provider. Required for cloud providers " +
                    "(anthropic, openai, gemini). Optional/unused for local LLMs (ollama, jlama). " +
                    "Can be a vault reference like '${vault:openai-key}'.") String apiKey,
            @ToolArg(description = "Base URL for the LLM provider (optional). " +
                    "Useful for ollama when running in Docker (e.g. 'http://host.docker.internal:11434')") String baseUrl,
            @ToolArg(description = "Greeting message shown when a conversation starts (optional)") String introMessage,
            @ToolArg(description = "Enable built-in tools like calculator, datetime, websearch? (default: false)") Boolean enableBuiltInTools,
            @ToolArg(description = "Comma-separated list of specific built-in tools to enable " +
                    "(e.g. 'calculator,datetime,websearch'). Only used if enableBuiltInTools is true.") String builtInToolsWhitelist,
            @ToolArg(description = "Enable quick reply buttons in bot responses? (default: false). " +
                    "When enabled, the LLM returns structured JSON with quick reply suggestions. " +
                    "Note: streaming is not supported when this is enabled.") Boolean enableQuickReplies,
            @ToolArg(description = "Enable ad-hoc sentiment analysis in bot responses? (default: false). " +
                    "When enabled, the LLM returns structured JSON with sentiment scores, " +
                    "emotion detection, intent classification, and urgency rating. " +
                    "Note: streaming is not supported when this is enabled.") Boolean enableSentimentAnalysis,
            @ToolArg(description = "Automatically deploy the bot after creation? (default: true)") Boolean deploy,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        try {
            // Validate required params
            if (name == null || name.isBlank()) {
                return errorJson("Bot name is required");
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                return errorJson("System prompt is required");
            }
            // API key required for cloud LLM providers, optional for local ones
            boolean isLocalLLM = isLocalLlmProvider(model);
            if (!isLocalLLM && (apiKey == null || apiKey.isBlank())) {
                return errorJson("API key is required for cloud LLM providers (anthropic, openai, gemini)");
            }

            var params = resolveParams(model, modelName, deploy, environment);
            boolean toolsEnabled = enableBuiltInTools != null && enableBuiltInTools;
            boolean quickReplies = enableQuickReplies != null && enableQuickReplies;
            boolean sentiment = enableSentimentAnalysis != null && enableSentimentAnalysis;
            String promptResponseJson = buildPromptResponseJson(quickReplies, sentiment);

            var createdResources = new LinkedHashMap<String, String>(); // track for result

            // --- Step 1: Create Behavior Rules ---
            var behaviorConfig = createBehaviorConfig();
            Response behaviorResponse = getRestStore(IRestBehaviorStore.class).createBehaviorRuleSet(behaviorConfig);
            String behaviorLocation = behaviorResponse.getHeaderString("Location");
            String behaviorId = extractIdFromLocation(behaviorLocation);
            int behaviorVersion = extractVersionFromLocation(behaviorLocation);
            createdResources.put("behaviorLocation", behaviorLocation);
            patchDescriptor(behaviorId, behaviorVersion, name);

            // --- Step 2: Create LangChain Configuration ---
            var langchainConfig = createLangchainConfig(
                    params.modelType, params.modelId, apiKey, systemPrompt,
                    toolsEnabled, builtInToolsWhitelist, baseUrl, promptResponseJson);
            Response langchainResponse = getRestStore(IRestLangChainStore.class).createLangChain(langchainConfig);
            String langchainLocation = langchainResponse.getHeaderString("Location");
            String langchainId = extractIdFromLocation(langchainLocation);
            int langchainVersion = extractVersionFromLocation(langchainLocation);
            createdResources.put("langchainLocation", langchainLocation);
            patchDescriptor(langchainId, langchainVersion, name);

            // --- Step 3: Create Output Set (if intro message provided) ---
            String outputLocation = null;
            if (introMessage != null && !introMessage.isBlank()) {
                var outputConfig = createOutputConfig(introMessage);
                Response outputResponse = getRestStore(IRestOutputStore.class).createOutputSet(outputConfig);
                outputLocation = outputResponse.getHeaderString("Location");
                String outputId = extractIdFromLocation(outputLocation);
                int outputVersion = extractVersionFromLocation(outputLocation);
                createdResources.put("outputLocation", outputLocation);
                patchDescriptor(outputId, outputVersion, name);
            }

            // --- Step 4: Create Package ---
            var packageConfig = createPackageConfig(behaviorLocation, null, langchainLocation, outputLocation);
            Response packageResponse = getRestStore(IRestPackageStore.class).createPackage(packageConfig);
            String packageLocation = packageResponse.getHeaderString("Location");
            String packageId = extractIdFromLocation(packageLocation);
            int packageVersion = extractVersionFromLocation(packageLocation);
            createdResources.put("packageLocation", packageLocation);
            patchDescriptor(packageId, packageVersion, name);

            // --- Step 5: Create Bot ---
            var botConfig = new BotConfiguration();
            botConfig.setPackages(List.of(URI.create(packageLocation)));
            Response botResponse = getRestStore(IRestBotStore.class).createBot(botConfig);
            String botLocation = botResponse.getHeaderString("Location");
            String botId = extractIdFromLocation(botLocation);
            int botVersion = extractVersionFromLocation(botLocation);
            createdResources.put("botLocation", botLocation);
            patchDescriptor(botId, botVersion, name);

            // --- Step 6: Deploy (synchronous wait for completion) ---
            if (params.shouldDeploy && botId != null) {
                var deployResult = deployAndWait(params.env, botId, botVersion);
                createdResources.putAll(deployResult);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("action", "setup_complete");
            result.put("botId", botId != null ? botId : "unknown");
            result.put("botName", name);
            result.put("model", params.modelType + "/" + params.modelId);
            if (quickReplies || sentiment) {
                result.put("responseFormat", "json");
                if (quickReplies) result.put("quickRepliesEnabled", true);
                if (sentiment) result.put("sentimentAnalysisEnabled", true);
            }
            result.put("resources", createdResources);
            return jsonSerialization.serialize(result);

        } catch (Exception e) {
            LOGGER.error("MCP setup_bot failed", e);
            return errorJson("Failed to set up bot: " + e.getMessage());
        }
    }

    // --- Config Builders ---

    /**
     * Create behavior rules: catch-all inputmatcher(*) → send_message action.
     * This is the standard Bot Father pattern.
     */
    BehaviorConfiguration createBehaviorConfig() {
        var condition = new BehaviorRuleConditionConfiguration();
        condition.setType("inputmatcher");
        condition.setConfigs(Map.of("expressions", "*"));

        var rule = new BehaviorRuleConfiguration();
        rule.setName("Send Message to LLM");
        rule.setActions(List.of("send_message"));
        rule.setConditions(List.of(condition));

        var group = new BehaviorGroupConfiguration();
        group.setBehaviorRules(List.of(rule));

        var config = new BehaviorConfiguration();
        config.setExpressionsAsActions(true);
        config.setBehaviorGroups(List.of(group));
        return config;
    }

    /**
     * Create LangChain config with the specified model, system prompt, and tool
     * settings.
     * Uses provider-specific parameter key names (e.g., Ollama uses 'model' not
     * 'modelName').
     */
    LangChainConfiguration createLangchainConfig(String modelType, String modelId,
            String apiKey, String systemPrompt,
            boolean enableTooling, String toolsWhitelist,
            String baseUrl, String promptResponseJson) {
        var task = new LangChainConfiguration.Task();
        task.setActions(List.of("send_message"));
        task.setId(modelType);
        task.setType(modelType);
        task.setDescription("LLM integration via " + modelType);

        // Build effective system message: user prompt + optional JSON format instruction
        String effectiveSystemPrompt = systemPrompt;
        if (promptResponseJson != null) {
            effectiveSystemPrompt = systemPrompt + "\n\n" + promptResponseJson;
        }

        var params = new LinkedHashMap<String, String>();
        params.put("systemMessage", effectiveSystemPrompt);
        params.put("addToOutput", "true");
        params.put("timeout", "60");
        params.put("temperature", "0.3");
        params.put("logRequests", "true");
        params.put("logResponses", "true");

        // When JSON response format is active, parse the response as a JSON object
        if (promptResponseJson != null) {
            params.put("convertToObject", "true");
        }

        // Provider-specific parameter mapping
        switch (modelType) {
            case "ollama" -> {
                // Ollama uses 'model' not 'modelName', no apiKey needed
                params.put("model", modelId);
                if (baseUrl != null && !baseUrl.isBlank()) {
                    params.put("baseUrl", baseUrl);
                }
            }
            case "jlama" -> {
                // jlama uses 'modelName', optional 'authToken' instead of 'apiKey'
                params.put("modelName", modelId);
                if (apiKey != null && !apiKey.isBlank()) {
                    params.put("authToken", apiKey);
                }
            }
            default -> {
                // Cloud providers (anthropic, openai, gemini, etc.): 'modelName' + 'apiKey'
                params.put("modelName", modelId);
                if (apiKey != null && !apiKey.isBlank()) {
                    params.put("apiKey", apiKey);
                }
                if (baseUrl != null && !baseUrl.isBlank()) {
                    params.put("baseUrl", baseUrl);
                }
                // Set responseFormat=json for providers that support it (OpenAI, Gemini)
                if (promptResponseJson != null && supportsResponseFormat(modelType)) {
                    params.put("responseFormat", "json");
                }
            }
        }

        task.setParameters(params);

        if (enableTooling) {
            task.setEnableBuiltInTools(true);
            if (toolsWhitelist != null && !toolsWhitelist.isBlank()) {
                task.setBuiltInToolsWhitelist(
                        List.of(toolsWhitelist.split(",")).stream()
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList());
            }
        }

        task.setConversationHistoryLimit(10);
        return new LangChainConfiguration(List.of(task));
    }

    /**
     * Create output set with a CONVERSATION_START intro message.
     */
    OutputConfigurationSet createOutputConfig(String introMessage) {
        var textItem = new TextOutputItem(introMessage, 0);

        var output = new OutputConfiguration.Output();
        output.setValueAlternatives(List.of(textItem));

        var outputEntry = new OutputConfiguration();
        outputEntry.setAction("CONVERSATION_START");
        outputEntry.setTimesOccurred(0);
        outputEntry.setOutputs(List.of(output));

        var outputSet = new OutputConfigurationSet();
        outputSet.setOutputSet(List.of(outputEntry));
        return outputSet;
    }

    /**
     * Create package with behavior + [httpcalls...] + langchain [+ output]
     * pipeline. Parser is NOT included — LLM-powered bots don't need NLU parsing.
     */
    PackageConfiguration createPackageConfig(String behaviorLocation,
            List<String> httpCallsLocations,
            String langchainLocation,
            String outputLocation) {
        var extensions = new ArrayList<PackageConfiguration.PackageExtension>();

        // Note: Parser extension is NOT added here. LLM-powered bots (created by setup_bot)
        // don't need the NLU parser — the LangChain task processes user input directly.
        // Adding a parser without dictionary config causes deployment failures.

        // Behavior rules
        var behavior = new PackageConfiguration.PackageExtension();
        behavior.setType(URI.create("eddi://ai.labs.behavior"));
        behavior.setConfig(Map.of("uri", behaviorLocation));
        extensions.add(behavior);

        // HttpCalls (zero or more groups)
        if (httpCallsLocations != null) {
            for (String httpCallsLocation : httpCallsLocations) {
                var httpCalls = new PackageConfiguration.PackageExtension();
                httpCalls.setType(URI.create("eddi://ai.labs.httpcalls"));
                httpCalls.setConfig(Map.of("uri", httpCallsLocation));
                extensions.add(httpCalls);
            }
        }

        // LangChain
        var langchain = new PackageConfiguration.PackageExtension();
        langchain.setType(URI.create("eddi://ai.labs.langchain"));
        langchain.setConfig(Map.of("uri", langchainLocation));
        extensions.add(langchain);

        // Output (optional)
        if (outputLocation != null) {
            var output = new PackageConfiguration.PackageExtension();
            output.setType(URI.create("eddi://ai.labs.output"));
            output.setConfig(Map.of("uri", outputLocation));
            extensions.add(output);
        }

        var config = new PackageConfiguration();
        config.setPackageExtensions(extensions);
        return config;
    }

    @Tool(name = "create_api_bot", description = "Create a bot that can call any REST API described by an OpenAPI specification. "
            +
            "Parses the OpenAPI spec, generates HttpCalls configurations (grouped by API tag), " +
            "and creates a fully deployed bot with LLM-powered API interaction. " +
            "The LLM can then call the API endpoints as tools through EDDI's controlled pipeline.")
    public String createApiBot(
            @ToolArg(description = "Bot name (required)") String name,
            @ToolArg(description = "System prompt / role for the LLM (required). " +
                    "Should describe the API and how the bot should use it.") String systemPrompt,
            @ToolArg(description = "OpenAPI 3.0/3.1 specification as JSON/YAML string or URL (required)") String openApiSpec,
            @ToolArg(description = "LLM provider type: 'anthropic' (default), 'openai', or 'gemini'") String model,
            @ToolArg(description = "Model name, e.g. 'claude-sonnet-4-6' (default), 'gpt-5.4', 'gemini-3.1-pro-preview', 'deepseek-chat'") String modelName,
            @ToolArg(description = "API key for the LLM provider (required). " +
                    "Can be a vault reference like '${vault:openai-key}'") String apiKey,
            @ToolArg(description = "Override the API base URL from the spec (optional)") String apiBaseUrl,
            @ToolArg(description = "API authorization header value or vault reference (optional). " +
                    "E.g. 'Bearer sk-...' or '${vault:api-key}'") String apiAuth,
            @ToolArg(description = "Comma-separated endpoint filter, e.g. 'GET /users,POST /orders' (optional, default: all)") String endpoints,
            @ToolArg(description = "Automatically deploy the bot after creation? (default: true)") Boolean deploy,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        try {
            // Validate required params
            if (name == null || name.isBlank()) {
                return errorJson("Bot name is required");
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                return errorJson("System prompt is required");
            }
            if (openApiSpec == null || openApiSpec.isBlank()) {
                return errorJson("OpenAPI spec is required");
            }
            if (apiKey == null || apiKey.isBlank()) {
                return errorJson("API key is required");
            }

            var params = resolveParams(model, modelName, deploy, environment);

            var createdResources = new LinkedHashMap<String, Object>();

            // --- Step 1: Parse OpenAPI and build grouped httpcalls configs ---
            McpApiToolBuilder.ApiBuildResult buildResult;
            try {
                buildResult = McpApiToolBuilder.parseAndBuild(openApiSpec, endpoints, apiBaseUrl, apiAuth);
            } catch (IllegalArgumentException e) {
                return errorJson("OpenAPI parsing failed: " + e.getMessage());
            }

            // --- Step 2: Create HttpCalls resources (one per group) ---
            var httpCallsLocations = new ArrayList<String>();
            var groupNames = new ArrayList<String>();
            for (var entry : buildResult.configsByGroup().entrySet()) {
                String groupName = entry.getKey();
                var config = entry.getValue();
                Response httpCallsResponse = getRestStore(IRestHttpCallsStore.class).createHttpCalls(config);
                String httpCallsLocation = httpCallsResponse.getHeaderString("Location");
                httpCallsLocations.add(httpCallsLocation);
                groupNames.add(groupName);

                // Patch descriptor with "{botName} - {groupName}"
                String httpCallsId = extractIdFromLocation(httpCallsLocation);
                int httpCallsVersion = extractVersionFromLocation(httpCallsLocation);
                patchDescriptor(httpCallsId, httpCallsVersion, name + " - " + groupName);
            }
            createdResources.put("httpCallsGroups", groupNames);
            createdResources.put("httpCallsLocations", httpCallsLocations);

            // --- Step 3: Create Behavior Rules ---
            var behaviorConfig = createBehaviorConfig();
            Response behaviorResponse = getRestStore(IRestBehaviorStore.class).createBehaviorRuleSet(behaviorConfig);
            String behaviorLocation = behaviorResponse.getHeaderString("Location");
            createdResources.put("behaviorLocation", behaviorLocation);
            patchDescriptor(extractIdFromLocation(behaviorLocation),
                    extractVersionFromLocation(behaviorLocation), name);

            // --- Step 4: Create LangChain Configuration ---
            // Enrich system prompt with API summary
            String enrichedPrompt = systemPrompt + "\n\n" + buildResult.apiSummary();
            var langchainConfig = createLangchainConfig(
                    params.modelType, params.modelId, apiKey, enrichedPrompt, false, null, null, null);
            Response langchainResponse = getRestStore(IRestLangChainStore.class).createLangChain(langchainConfig);
            String langchainLocation = langchainResponse.getHeaderString("Location");
            createdResources.put("langchainLocation", langchainLocation);
            patchDescriptor(extractIdFromLocation(langchainLocation),
                    extractVersionFromLocation(langchainLocation), name);

            // --- Step 5: Create Package (with httpcalls in pipeline) ---
            var packageConfig = createPackageConfig(
                    behaviorLocation, httpCallsLocations, langchainLocation, null);
            Response packageResponse = getRestStore(IRestPackageStore.class).createPackage(packageConfig);
            String packageLocation = packageResponse.getHeaderString("Location");
            createdResources.put("packageLocation", packageLocation);
            patchDescriptor(extractIdFromLocation(packageLocation),
                    extractVersionFromLocation(packageLocation), name);

            // --- Step 6: Create Bot ---
            var botConfig = new BotConfiguration();
            botConfig.setPackages(List.of(URI.create(packageLocation)));
            Response botResponse = getRestStore(IRestBotStore.class).createBot(botConfig);
            String botLocation = botResponse.getHeaderString("Location");
            String botId = extractIdFromLocation(botLocation);
            int botVersion = extractVersionFromLocation(botLocation);
            createdResources.put("botLocation", botLocation);
            patchDescriptor(botId, botVersion, name);

            // --- Step 7: Deploy (synchronous wait for completion) ---
            if (params.shouldDeploy && botId != null) {
                var deployResult = deployAndWait(params.env, botId, botVersion);
                createdResources.putAll(deployResult);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("action", "api_bot_created");
            result.put("botId", botId != null ? botId : "unknown");
            result.put("botName", name);
            result.put("model", params.modelType + "/" + params.modelId);
            result.put("endpointCount", buildResult.endpointCount());
            result.put("groups", groupNames);
            result.put("resources", createdResources);
            return jsonSerialization.serialize(result);

        } catch (Exception e) {
            LOGGER.error("MCP create_api_bot failed", e);
            return errorJson("Failed to create API bot: " + e.getMessage());
        }
    }

    /**
     * Resolve common parameters with defaults.
     */
    private record ResolvedParams(String modelType, String modelId,
            boolean shouldDeploy, Deployment.Environment env) {
    }

    private ResolvedParams resolveParams(String model, String modelName,
            Boolean deploy, String environment) {
        return new ResolvedParams(
                model != null && !model.isBlank() ? model.trim().toLowerCase() : "anthropic",
                modelName != null && !modelName.isBlank() ? modelName.trim() : "claude-sonnet-4-6",
                deploy == null || deploy,
                parseEnvironment(environment));
    }

    /**
     * Deploy a bot using the REST endpoint with waitForCompletion=true.
     * The endpoint waits up to 30s for deployment to complete and returns the actual status.
     */
    private Map<String, String> deployAndWait(Deployment.Environment env, String botId, int botVersion) {
        var result = new LinkedHashMap<String, String>();
        result.put("environment", env.name());
        try {
            Response response = botAdmin.deployBot(env, botId, botVersion, true, true);
            int httpStatus = response.getStatus();

            if (httpStatus == 200) {
                // Synchronous response with actual deployment status
                try {
                    @SuppressWarnings("unchecked")
                    var body = (java.util.Map<String, Object>) response.getEntity();
                    String deployStatus = body != null && body.containsKey("status")
                            ? body.get("status").toString() : "UNKNOWN";
                    result.put("deployed", "READY".equals(deployStatus) ? "true" : "false");
                    result.put("deploymentStatus", deployStatus);
                    if (body != null && body.containsKey("error")) {
                        result.put("deployError", body.get("error").toString());
                    }
                    if (!"READY".equals(deployStatus)) {
                        String warning = "Bot created but deployment status is " + deployStatus +
                                ". Check bot configuration and credentials.";
                        if (body != null && body.containsKey("error")) {
                            warning += " Error: " + body.get("error");
                        }
                        result.put("deployWarning", warning);
                    }
                } catch (Exception parseError) {
                    LOGGER.debug("Could not parse deploy response", parseError);
                    result.put("deployed", "false");
                    result.put("deploymentStatus", "UNKNOWN");
                    result.put("deployWarning", "Deploy returned 200 but could not parse status.");
                }
            } else if (httpStatus == 202) {
                // Async response (shouldn't happen with waitForCompletion=true, but handle it)
                result.put("deployed", "false");
                result.put("deploymentStatus", "IN_PROGRESS");
                result.put("deployWarning", "Deployment accepted but not yet complete.");
            } else {
                result.put("deployed", "false");
                result.put("deployError", "Unexpected deploy response: HTTP " + httpStatus);
            }
        } catch (Exception deployError) {
            LOGGER.warn("MCP deploy failed for bot " + botId, deployError);
            result.put("deployed", "false");
            result.put("deployError", "Deployment failed. Check server logs for details.");
        }
        return result;
    }

    /**
     * Check if the given model type is a local LLM provider (no API key needed).
     */
    static boolean isLocalLlmProvider(String model) {
        if (model == null || model.isBlank())
            return false;
        String normalized = model.trim().toLowerCase();
        return "ollama".equals(normalized) || "jlama".equals(normalized);
    }

    /**
     * Check if the provider supports the builder-level responseFormat=json parameter.
     * Providers that support this will enforce JSON output at the API level,
     * making structured responses more reliable (especially with smaller models).
     */
    static boolean supportsResponseFormat(String modelType) {
        return "openai".equals(modelType) || "gemini".equals(modelType) || "gemini-vertex".equals(modelType);
    }

    /**
     * Build the promptResponseJson format instruction for the LLM.
     * Returns null if neither feature is enabled.
     *
     * <p>This follows the same pattern as the Gnowbe learner bot:
     * the instruction tells the LLM to respond with a single valid JSON object
     * containing the main text reply and optional structured data.</p>
     *
     * @param quickReplies include quick reply button suggestions
     * @param sentiment    include sentiment analysis fields
     * @return the format instruction string, or null if no features enabled
     */
    static String buildPromptResponseJson(boolean quickReplies, boolean sentiment) {
        if (!quickReplies && !sentiment) {
            return null;
        }

        // Build the JSON schema as a map — cleaner than manual string escaping
        var schema = new LinkedHashMap<String, Object>();
        schema.put("htmlResponseText",
                "String - your main reply to the user, optionally formatted with basic inline HTML tags for readability.");

        if (quickReplies) {
            schema.put("quickReplies", List.of(
                    "short, button-like suggestions for how the user might want to respond next: " +
                    "Provide 2-4 concise quick reply buttons that are relevant to your latest answer " +
                    "and any recent user input. They should prompt fast responses or encourage deeper " +
                    "exploration (e.g., 'Yes, I agree', 'Tell me more')"));
        }

        if (sentiment) {
            schema.put("sentimentScore", "Float - range from -1.0 (very negative) to +1.0 (very positive)");
            schema.put("userSentimentTrend", "String - e.g., 'improved', 'worsened', or 'unchanged'");
            schema.put("identifiedEmotions", List.of("String - e.g., 'anger', 'joy', 'frustration', etc."));
            schema.put("detectedIntent", "String - e.g., 'complaint', 'question', 'feedback', 'feature_request'");
            schema.put("urgencyRating", "String - e.g., 'low', 'medium', or 'high'");
            schema.put("extractedUserFeedback", "String - direct user feedback if present; otherwise empty");
        }

        try {
            String jsonSchema = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(schema);
            return "Response with one single valid JSON Object (without wrapping it in " +
                    "any formatting or markdown). Always use the following json structure as response:" +
                    jsonSchema;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Should never happen with simple maps/strings
            throw new RuntimeException("Failed to serialize JSON schema", e);
        }
    }

    /**
     * Patch a resource descriptor with the bot name.
     * Descriptors are auto-created by DocumentDescriptorFilter when using REST HTTP proxies.
     */
    private void patchDescriptor(String id, int version, String name) {
        if (id == null)
            return;
        try {
            var patchDoc = new DocumentDescriptor();
            patchDoc.setName(name);

            var patch = new PatchInstruction<DocumentDescriptor>();
            patch.setOperation(PatchInstruction.PatchOperation.SET);
            patch.setDocument(patchDoc);
            getRestStore(IRestDocumentDescriptorStore.class).patchDescriptor(id, version, patch);
        } catch (Exception e) {
            LOGGER.warn("MCP patchDescriptor failed for " + id, e);
        }
    }

    /**
     * Get a REST interface proxy via IRestInterfaceFactory.
     * These proxies make HTTP calls that go through the full JAX-RS pipeline,
     * including DocumentDescriptorFilter which auto-creates descriptors.
     */
    private <T> T getRestStore(Class<T> clazz) {
        try {
            return restInterfaceFactory.get(clazz);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            throw new RuntimeException("Failed to get REST proxy for " + clazz.getSimpleName(), e);
        }
    }
}
