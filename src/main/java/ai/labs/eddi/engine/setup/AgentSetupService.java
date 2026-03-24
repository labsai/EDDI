package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConditionConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.apicalls.model.OutputBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.QuickRepliesBuildingInstruction;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;

/**
 * Service that encapsulates the business logic for setting up EDDI agents.
 * Used by both the MCP {@code setup_agent}/{@code create_api_agent} tools
 * and the REST {@code POST /administration/agents/setup*} endpoints.
 *
 * <p>This replaces the former monolithic approach where all logic lived
 * inside MCP {@code @Tool} annotated methods.</p>
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentSetupService {

    private static final Logger LOGGER = Logger.getLogger(AgentSetupService.class);

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestAgentAdministration agentAdmin;

    @Inject
    public AgentSetupService(IRestInterfaceFactory restInterfaceFactory,
                             IRestAgentAdministration agentAdmin) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.agentAdmin = agentAdmin;
    }

    /**
     * Create a fully configured and optionally deployed agent.
     * Equivalent to the Agent Father's 12-step workflow.
     *
     * @param request the setup parameters
     * @return result with created resource IDs
     * @throws AgentSetupException if the setup fails
     */
    public SetupResult setupAgent(SetupAgentRequest request) throws AgentSetupException {
        // Validate required params
        if (request.name() == null || request.name().isBlank()) {
            throw new AgentSetupException("Agent name is required");
        }
        if (request.systemPrompt() == null || request.systemPrompt().isBlank()) {
            throw new AgentSetupException("System prompt is required");
        }
        boolean isLocalLLM = isLocalLlmProvider(request.provider());
        if (!isLocalLLM && (request.apiKey() == null || request.apiKey().isBlank())) {
            throw new AgentSetupException("API key is required for cloud LLM providers (anthropic, openai, gemini)");
        }

        var params = resolveParams(request.provider(), request.model(), request.deploy(), request.environment());
        boolean toolsEnabled = request.enableBuiltInTools() != null && request.enableBuiltInTools();
        boolean quickReplies = request.enableQuickReplies() != null && request.enableQuickReplies();
        boolean sentiment = request.enableSentimentAnalysis() != null && request.enableSentimentAnalysis();
        String promptResponseJson = buildPromptResponseJson(quickReplies, sentiment);

        var createdResources = new LinkedHashMap<String, Object>();

        try {
            // --- Step 1: Create Parser ---
            var parserConfig = createParserConfig();
            Response parserResponse = getRestStore(IRestParserStore.class).createParser(parserConfig);
            String parserLocation = parserResponse.getHeaderString("Location");
            String parserId = extractIdFromLocation(parserLocation);
            int parserVersion = extractVersionFromLocation(parserLocation);
            createdResources.put("parserLocation", parserLocation);
            patchDescriptor(parserId, parserVersion, request.name());

            // --- Step 2: Create Behavior Rules ---
            var behaviorConfig = createBehaviorConfig();
            Response behaviorResponse = getRestStore(IRestRuleSetStore.class).createRuleSet(behaviorConfig);
            String behaviorLocation = behaviorResponse.getHeaderString("Location");
            String behaviorId = extractIdFromLocation(behaviorLocation);
            int behaviorVersion = extractVersionFromLocation(behaviorLocation);
            createdResources.put("behaviorLocation", behaviorLocation);
            patchDescriptor(behaviorId, behaviorVersion, request.name());

            // --- Step 3: Create LLM Configuration ---
            var llmConfig = createLlmConfig(
                    params.providerType, params.modelId, request.apiKey(), request.systemPrompt(),
                    toolsEnabled, request.builtInToolsWhitelist(), request.baseUrl(), promptResponseJson,
                    quickReplies, sentiment, request.mcpServers());
            Response llmResponse = getRestStore(IRestLlmStore.class).createLlm(llmConfig);
            String langchainLocation = llmResponse.getHeaderString("Location");
            String langchainId = extractIdFromLocation(langchainLocation);
            int langchainVersion = extractVersionFromLocation(langchainLocation);
            createdResources.put("langchainLocation", langchainLocation);
            patchDescriptor(langchainId, langchainVersion, request.name());

            // --- Step 4: Create Output Set (if intro message provided) ---
            String outputLocation = null;
            if (request.introMessage() != null && !request.introMessage().isBlank()) {
                var outputConfig = createOutputConfig(request.introMessage());
                Response outputResponse = getRestStore(IRestOutputStore.class).createOutputSet(outputConfig);
                outputLocation = outputResponse.getHeaderString("Location");
                String outputId = extractIdFromLocation(outputLocation);
                int outputVersion = extractVersionFromLocation(outputLocation);
                createdResources.put("outputLocation", outputLocation);
                patchDescriptor(outputId, outputVersion, request.name());
            }

            // --- Step 5: Create Workflow ---
            var workflowConfig = createWorkflowConfig(parserLocation, behaviorLocation, null, langchainLocation,
                    outputLocation);
            Response workflowResponse = getRestStore(IRestWorkflowStore.class).createWorkflow(workflowConfig);
            String workflowLocation = workflowResponse.getHeaderString("Location");
            String workflowId = extractIdFromLocation(workflowLocation);
            int workflowVersion = extractVersionFromLocation(workflowLocation);
            createdResources.put("packageLocation", workflowLocation);
            patchDescriptor(workflowId, workflowVersion, request.name());

            // --- Step 6: Create Agent ---
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create(workflowLocation)));
            Response agentResponse = getRestStore(IRestAgentStore.class).createAgent(agentConfig);
            String agentLocation = agentResponse.getHeaderString("Location");
            String agentId = extractIdFromLocation(agentLocation);
            int agentVersion = extractVersionFromLocation(agentLocation);
            createdResources.put("agentLocation", agentLocation);
            patchDescriptor(agentId, agentVersion, request.name());

            // --- Step 7: Deploy ---
            var resultBuilder = SetupResult.builder()
                    .action("setup_complete")
                    .agentId(agentId != null ? agentId : "unknown")
                    .agentName(request.name())
                    .provider(params.providerType)
                    .model(params.modelId);

            if (quickReplies) resultBuilder.quickRepliesEnabled(true);
            if (sentiment) resultBuilder.sentimentAnalysisEnabled(true);

            if (params.shouldDeploy && agentId != null) {
                var deployResult = deployAndWait(params.env, agentId, agentVersion);
                createdResources.putAll(deployResult);
                resultBuilder.deployed((Boolean) deployResult.getOrDefault("deployed", false));
                resultBuilder.deploymentStatus((String) deployResult.getOrDefault("deploymentStatus", "UNKNOWN"));
            }

            resultBuilder.resources(createdResources);
            return resultBuilder.build();

        } catch (Exception e) {
            throw new AgentSetupException("Failed to set up agent: " + e.getMessage(), e);
        }
    }

    /**
     * Create an API agent from an OpenAPI specification.
     * Parses the spec, generates ApiCalls configurations grouped by tag,
     * and creates a fully deployed agent.
     *
     * @param request the API agent creation parameters
     * @return result with created resource IDs
     * @throws AgentSetupException if the setup fails
     */
    public SetupResult createApiAgent(CreateApiAgentRequest request) throws AgentSetupException {
        // Validate required params
        if (request.name() == null || request.name().isBlank()) {
            throw new AgentSetupException("Agent name is required");
        }
        if (request.systemPrompt() == null || request.systemPrompt().isBlank()) {
            throw new AgentSetupException("System prompt is required");
        }
        if (request.openApiSpec() == null || request.openApiSpec().isBlank()) {
            throw new AgentSetupException("OpenAPI spec is required");
        }
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new AgentSetupException("API key is required");
        }

        var params = resolveParams(request.provider(), request.model(), request.deploy(), request.environment());
        var createdResources = new LinkedHashMap<String, Object>();

        try {
            // --- Step 1: Parse OpenAPI and build grouped httpcalls configs ---
            McpApiToolBuilder.ApiBuildResult buildResult;
            try {
                buildResult = McpApiToolBuilder.parseAndBuild(
                        request.openApiSpec(), request.endpoints(), request.apiBaseUrl(), request.apiAuth());
            } catch (IllegalArgumentException e) {
                throw new AgentSetupException("OpenAPI parsing failed: " + e.getMessage(), e);
            }

            // --- Step 2: Create ApiCalls resources (one per group) ---
            var httpCallsLocations = new ArrayList<String>();
            var groupNames = new ArrayList<String>();
            for (var entry : buildResult.configsByGroup().entrySet()) {
                String groupName = entry.getKey();
                var config = entry.getValue();
                Response httpCallsResponse = getRestStore(IRestApiCallsStore.class).createApiCalls(config);
                String httpCallsLocation = httpCallsResponse.getHeaderString("Location");
                httpCallsLocations.add(httpCallsLocation);
                groupNames.add(groupName);

                String httpCallsId = extractIdFromLocation(httpCallsLocation);
                int httpCallsVersion = extractVersionFromLocation(httpCallsLocation);
                patchDescriptor(httpCallsId, httpCallsVersion, request.name() + " - " + groupName);
            }
            createdResources.put("httpCallsGroups", groupNames);
            createdResources.put("httpCallsLocations", httpCallsLocations);

            // --- Step 3: Create Parser ---
            var parserConfig = createParserConfig();
            Response parserResponse = getRestStore(IRestParserStore.class).createParser(parserConfig);
            String parserLocation = parserResponse.getHeaderString("Location");
            createdResources.put("parserLocation", parserLocation);
            patchDescriptor(extractIdFromLocation(parserLocation),
                    extractVersionFromLocation(parserLocation), request.name());

            // --- Step 4: Create Behavior Rules ---
            var behaviorConfig = createBehaviorConfig();
            Response behaviorResponse = getRestStore(IRestRuleSetStore.class).createRuleSet(behaviorConfig);
            String behaviorLocation = behaviorResponse.getHeaderString("Location");
            createdResources.put("behaviorLocation", behaviorLocation);
            patchDescriptor(extractIdFromLocation(behaviorLocation),
                    extractVersionFromLocation(behaviorLocation), request.name());

            // --- Step 5: Create LLM Configuration ---
            String enrichedPrompt = request.systemPrompt() + "\n\n" + buildResult.apiSummary();
            boolean quickReplies = request.enableQuickReplies() != null && request.enableQuickReplies();
            boolean sentiment = request.enableSentimentAnalysis() != null && request.enableSentimentAnalysis();
            String promptResponseJson = buildPromptResponseJson(quickReplies, sentiment);
            var llmConfig = createLlmConfig(
                    params.providerType, params.modelId, request.apiKey(), enrichedPrompt,
                    false, null, null, promptResponseJson,
                    quickReplies, sentiment, null);
            Response llmResponse = getRestStore(IRestLlmStore.class).createLlm(llmConfig);
            String langchainLocation = llmResponse.getHeaderString("Location");
            createdResources.put("langchainLocation", langchainLocation);
            patchDescriptor(extractIdFromLocation(langchainLocation),
                    extractVersionFromLocation(langchainLocation), request.name());

            // --- Step 6: Create Workflow (with httpcalls in pipeline) ---
            var workflowConfig = createWorkflowConfig(
                    parserLocation, behaviorLocation, httpCallsLocations, langchainLocation, null);
            Response workflowResponse = getRestStore(IRestWorkflowStore.class).createWorkflow(workflowConfig);
            String workflowLocation = workflowResponse.getHeaderString("Location");
            createdResources.put("packageLocation", workflowLocation);
            patchDescriptor(extractIdFromLocation(workflowLocation),
                    extractVersionFromLocation(workflowLocation), request.name());

            // --- Step 7: Create Agent ---
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create(workflowLocation)));
            Response agentResponse = getRestStore(IRestAgentStore.class).createAgent(agentConfig);
            String agentLocation = agentResponse.getHeaderString("Location");
            String agentId = extractIdFromLocation(agentLocation);
            int agentVersion = extractVersionFromLocation(agentLocation);
            createdResources.put("agentLocation", agentLocation);
            patchDescriptor(agentId, agentVersion, request.name());

            // --- Step 8: Deploy ---
            var resultBuilder = SetupResult.builder()
                    .action("api_agent_created")
                    .agentId(agentId != null ? agentId : "unknown")
                    .agentName(request.name())
                    .provider(params.providerType)
                    .model(params.modelId)
                    .endpointCount(buildResult.endpointCount())
                    .groups(groupNames);

            if (params.shouldDeploy && agentId != null) {
                var deployResult = deployAndWait(params.env, agentId, agentVersion);
                createdResources.putAll(deployResult);
                resultBuilder.deployed((Boolean) deployResult.getOrDefault("deployed", false));
                resultBuilder.deploymentStatus((String) deployResult.getOrDefault("deploymentStatus", "UNKNOWN"));
            }

            resultBuilder.resources(createdResources);
            return resultBuilder.build();

        } catch (AgentSetupException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentSetupException("Failed to create API agent: " + e.getMessage(), e);
        }
    }

    // ==================== Config Builders ====================

    /**
     * Create a minimal parser config with basic built-in dictionaries.
     */
    public ParserConfiguration createParserConfig() {
        var config = new ParserConfiguration();
        config.setExtensions(Map.of(
                "dictionaries", List.of(),
                "corrections", List.of()));
        return config;
    }

    /**
     * Create behavior rules: catch-all inputmatcher(*) → send_message action.
     */
    public RuleSetConfiguration createBehaviorConfig() {
        var condition = new RuleConditionConfiguration();
        condition.setType("inputmatcher");
        condition.setConfigs(Map.of("expressions", "*"));

        var rule = new RuleConfiguration();
        rule.setName("Send Message to LLM");
        rule.setActions(List.of("send_message"));
        rule.setConditions(List.of(condition));

        var group = new RuleGroupConfiguration();
        group.setRules(List.of(rule));

        var config = new RuleSetConfiguration();
        config.setExpressionsAsActions(true);
        config.setBehaviorGroups(List.of(group));
        return config;
    }

    /**
     * Create LLM config with the specified model, system prompt, and tool settings.
     */
    public LlmConfiguration createLlmConfig(String modelType, String modelId,
                                              String apiKey, String systemPrompt,
                                              boolean enableTooling, String toolsWhitelist,
                                              String baseUrl, String promptResponseJson,
                                              boolean quickReplies, boolean sentiment,
                                              String mcpServers) {
        var task = new LlmConfiguration.Task();
        task.setActions(List.of("send_message"));
        task.setId(modelType);
        task.setType(modelType);
        task.setDescription("LLM integration via " + modelType);

        String effectiveSystemPrompt = systemPrompt;
        if (promptResponseJson != null) {
            effectiveSystemPrompt = systemPrompt + "\n\n" + promptResponseJson;
        }

        var params = new LinkedHashMap<String, String>();
        params.put("systemMessage", effectiveSystemPrompt);
        params.put("addToOutput", promptResponseJson == null ? "true" : "false");
        params.put("timeout", "60000");
        params.put("temperature", "0.3");
        params.put("logRequests", "true");
        params.put("logResponses", "true");

        if (promptResponseJson != null) {
            params.put("convertToObject", "true");
            task.setResponseObjectName("aiOutput");
        }

        switch (modelType) {
            case "ollama" -> {
                params.put("model", modelId);
                if (baseUrl != null && !baseUrl.isBlank()) {
                    params.put("baseUrl", baseUrl);
                }
            }
            case "jlama" -> {
                params.put("modelName", modelId);
                if (apiKey != null && !apiKey.isBlank()) {
                    params.put("authToken", apiKey);
                }
            }
            default -> {
                params.put("modelName", modelId);
                if (apiKey != null && !apiKey.isBlank()) {
                    params.put("apiKey", apiKey);
                }
                if (baseUrl != null && !baseUrl.isBlank()) {
                    params.put("baseUrl", baseUrl);
                }
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

        if (mcpServers != null && !mcpServers.isBlank()) {
            var mcpConfigs = new ArrayList<LlmConfiguration.McpServerConfig>();
            for (String serverUrl : mcpServers.split(",")) {
                String trimmed = serverUrl.trim();
                if (!trimmed.isEmpty()) {
                    var mcpConfig = new LlmConfiguration.McpServerConfig();
                    mcpConfig.setUrl(trimmed);
                    mcpConfigs.add(mcpConfig);
                }
            }
            if (!mcpConfigs.isEmpty()) {
                task.setMcpServers(mcpConfigs);
            }
        }

        if (promptResponseJson != null) {
            task.setPostResponse(buildPostResponse(quickReplies, sentiment));
        }

        return new LlmConfiguration(List.of(task));
    }

    /**
     * Build the postResponse that extracts structured data from LLM JSON output.
     */
    public PostResponse buildPostResponse(boolean quickReplies, boolean sentiment) {
        var postResponse = new PostResponse();

        var outputInstruction = new OutputBuildingInstruction();
        outputInstruction.setIterationObjectName("obj");
        outputInstruction.setTemplateFilterExpression("");
        outputInstruction.setOutputType("text");
        outputInstruction.setOutputValue("[(${aiOutput.htmlResponseText})]");
        postResponse.setOutputBuildInstructions(List.of(outputInstruction));

        if (quickReplies) {
            var qrInstruction = new QuickRepliesBuildingInstruction();
            qrInstruction.setPathToTargetArray("aiOutput.quickReplies");
            qrInstruction.setIterationObjectName("quickReply");
            qrInstruction.setTemplateFilterExpression("");
            qrInstruction.setQuickReplyValue("[(${quickReply})]");
            qrInstruction.setQuickReplyExpressions("trigger(quick_reply)");
            postResponse.setQrBuildInstructions(List.of(qrInstruction));
        }

        return postResponse;
    }

    /**
     * Create output set with a CONVERSATION_START intro message.
     */
    public OutputConfigurationSet createOutputConfig(String introMessage) {
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
     * Create workflow with parser + behavior + [httpcalls...] + langchain [+ output].
     */
    public WorkflowConfiguration createWorkflowConfig(String parserLocation,
                                                       String behaviorLocation,
                                                       List<String> httpCallsLocations,
                                                       String langchainLocation,
                                                       String outputLocation) {
        var extensions = new ArrayList<WorkflowConfiguration.WorkflowStep>();

        if (parserLocation != null) {
            var parser = new WorkflowConfiguration.WorkflowStep();
            parser.setType(URI.create("eddi://ai.labs.parser"));
            parser.setConfig(Map.of("uri", parserLocation));
            extensions.add(parser);
        }

        var behavior = new WorkflowConfiguration.WorkflowStep();
        behavior.setType(URI.create("eddi://ai.labs.rules"));
        behavior.setConfig(Map.of("uri", behaviorLocation));
        extensions.add(behavior);

        if (httpCallsLocations != null) {
            for (String httpCallsLocation : httpCallsLocations) {
                var httpCalls = new WorkflowConfiguration.WorkflowStep();
                httpCalls.setType(URI.create("eddi://ai.labs.apicalls"));
                httpCalls.setConfig(Map.of("uri", httpCallsLocation));
                extensions.add(httpCalls);
            }
        }

        var langchain = new WorkflowConfiguration.WorkflowStep();
        langchain.setType(URI.create("eddi://ai.labs.llm"));
        langchain.setConfig(Map.of("uri", langchainLocation));
        extensions.add(langchain);

        if (outputLocation != null) {
            var output = new WorkflowConfiguration.WorkflowStep();
            output.setType(URI.create("eddi://ai.labs.output"));
            output.setConfig(Map.of("uri", outputLocation));
            extensions.add(output);
        }

        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(extensions);
        return config;
    }

    // ==================== Static Utility Methods ====================

    /**
     * Check if the given provider is a local LLM (no API key needed).
     */
    public static boolean isLocalLlmProvider(String provider) {
        if (provider == null || provider.isBlank()) return false;
        String normalized = provider.trim().toLowerCase();
        return "ollama".equals(normalized) || "jlama".equals(normalized);
    }

    /**
     * Check if the provider supports builder-level responseFormat=json.
     */
    public static boolean supportsResponseFormat(String modelType) {
        return "openai".equals(modelType) || "gemini".equals(modelType) || "gemini-vertex".equals(modelType);
    }

    /**
     * Build the promptResponseJson format instruction for the LLM.
     * Returns null if neither feature is enabled.
     */
    public static String buildPromptResponseJson(boolean quickReplies, boolean sentiment) {
        if (!quickReplies && !sentiment) {
            return null;
        }

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
            var sentimentObj = new LinkedHashMap<String, Object>();
            sentimentObj.put("score", "Float - range from -1.0 (very negative) to +1.0 (very positive)");
            sentimentObj.put("trend", "String - e.g., 'improved', 'worsened', or 'unchanged'");
            sentimentObj.put("emotions", List.of("String - e.g., 'anger', 'joy', 'frustration', etc."));
            sentimentObj.put("intent", "String - e.g., 'complaint', 'question', 'feedback', 'feature_request'");
            sentimentObj.put("urgency", "String - 'low', 'medium', or 'high'");
            sentimentObj.put("confidence", "Float - 0.0 to 1.0, how confident you are in the sentiment assessment");
            sentimentObj.put("topicTags",
                    List.of("String - e.g., 'billing', 'shipping', 'product_quality', 'account'"));
            sentimentObj.put("userFeedback", "String - direct user feedback if present; otherwise empty");
            schema.put("sentiment", sentimentObj);
        }

        try {
            String jsonSchema = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(schema);
            return "Response with one single valid JSON Object (without wrapping it in " +
                    "any formatting or markdown). Always use the following json structure as response:" +
                    jsonSchema;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize JSON schema", e);
        }
    }

    // ==================== Private Helpers ====================

    record ResolvedParams(String providerType, String modelId,
                          boolean shouldDeploy, Deployment.Environment env) {
    }

    ResolvedParams resolveParams(String provider, String model,
                                        Boolean deploy, String environment) {
        return new ResolvedParams(
                provider != null && !provider.isBlank() ? provider.trim().toLowerCase() : "anthropic",
                model != null && !model.isBlank() ? model.trim() : "claude-sonnet-4-6",
                deploy == null || deploy,
                parseEnvironment(environment));
    }

    /**
     * Deploy a Agent and wait for completion.
     */
    Map<String, Object> deployAndWait(Deployment.Environment env, String agentId, int agentVersion) {
        var result = new LinkedHashMap<String, Object>();
        result.put("environment", env.name());
        try {
            Response response = agentAdmin.deployAgent(env, agentId, agentVersion, true, true);
            int httpStatus = response.getStatus();

            if (httpStatus == 200) {
                try {
                    @SuppressWarnings("unchecked")
                    var body = (java.util.Map<String, Object>) response.getEntity();
                    String deployStatus = body != null && body.containsKey("status")
                            ? body.get("status").toString()
                            : "UNKNOWN";
                    result.put("deployed", "READY".equals(deployStatus));
                    result.put("deploymentStatus", deployStatus);
                    if (body != null && body.containsKey("error")) {
                        result.put("deployError", body.get("error").toString());
                    }
                    if (!"READY".equals(deployStatus)) {
                        String warning = "Agent created but deployment status is " + deployStatus +
                                ". Check Agent configuration and credentials.";
                        if (body != null && body.containsKey("error")) {
                            warning += " Error: " + body.get("error");
                        }
                        result.put("deployWarning", warning);
                    }
                } catch (Exception parseError) {
                    LOGGER.debug("Could not parse deploy response", parseError);
                    result.put("deployed", false);
                    result.put("deploymentStatus", "UNKNOWN");
                    result.put("deployWarning", "Deploy returned 200 but could not parse status.");
                }
            } else if (httpStatus == 202) {
                result.put("deployed", false);
                result.put("deploymentStatus", "IN_PROGRESS");
                result.put("deployWarning", "Deployment accepted but not yet complete.");
            } else {
                result.put("deployed", false);
                result.put("deployError", "Unexpected deploy response: HTTP " + httpStatus);
            }
        } catch (Exception deployError) {
            LOGGER.warn("Deploy failed for Agent " + agentId, deployError);
            result.put("deployed", false);
            result.put("deployError", "Deployment failed. Check server logs for details.");
        }
        return result;
    }

    private void patchDescriptor(String id, int version, String name) {
        if (id == null) return;
        try {
            var patchDoc = new DocumentDescriptor();
            patchDoc.setName(name);

            var patch = new PatchInstruction<DocumentDescriptor>();
            patch.setOperation(PatchInstruction.PatchOperation.SET);
            patch.setDocument(patchDoc);
            getRestStore(IRestDocumentDescriptorStore.class).patchDescriptor(id, version, patch);
        } catch (Exception e) {
            LOGGER.warn("patchDescriptor failed for " + id, e);
        }
    }

    private <T> T getRestStore(Class<T> clazz) {
        try {
            return restInterfaceFactory.get(clazz);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            throw new RuntimeException("Failed to get REST proxy for " + clazz.getSimpleName(), e);
        }
    }

    static Deployment.Environment parseEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return Deployment.Environment.production;
        }
        try {
            return Deployment.Environment.valueOf(environment.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return Deployment.Environment.production;
        }
    }

    static String extractIdFromLocation(String location) {
        if (location == null || location.isBlank()) return null;
        String path = location.contains("?") ? location.substring(0, location.indexOf('?')) : location;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < path.length() - 1
                ? path.substring(lastSlash + 1)
                : null;
    }

    static int extractVersionFromLocation(String location) {
        if (location == null || !location.contains("version=")) return 1;
        try {
            int idx = location.indexOf("version=") + "version=".length();
            int end = location.indexOf('&', idx);
            String ver = end > 0 ? location.substring(idx, end) : location.substring(idx);
            return Integer.parseInt(ver.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Exception thrown when agent setup fails.
     */
    public static class AgentSetupException extends Exception {
        public AgentSetupException(String message) {
            super(message);
        }

        public AgentSetupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
