package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Executes the tool-calling agent loop against a ChatModel.
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Collect enabled built-in tools based on task configuration</li>
 * <li>Build tool specifications and executors via reflection</li>
 * <li>Run the iterative tool-calling loop (max 10 iterations)</li>
 * <li>Manage budget checks, rate limiting, caching, and cost tracking via
 * ToolExecutionService</li>
 * <li>Produce an execution trace for debugging</li>
 * </ul>
 */
class AgentOrchestrator {
    private static final Logger LOGGER = Logger.getLogger(AgentOrchestrator.class);
    private static final String HTTPCALLS_TYPE = "eddi://ai.labs.httpcalls";

    // Built-in tools
    private final CalculatorTool calculatorTool;
    private final DateTimeTool dateTimeTool;
    private final WebSearchTool webSearchTool;
    private final DataFormatterTool dataFormatterTool;
    private final WebScraperTool webScraperTool;
    private final TextSummarizerTool textSummarizerTool;
    private final PdfReaderTool pdfReaderTool;
    private final WeatherTool weatherTool;
    private final ToolExecutionService toolExecutionService;
    private final McpToolProviderManager mcpToolProviderManager;

    // For httpcall auto-discovery from workflow
    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IApiCallExecutor apiCallExecutor;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;

    AgentOrchestrator(CalculatorTool calculatorTool, DateTimeTool dateTimeTool, WebSearchTool webSearchTool, DataFormatterTool dataFormatterTool,
            WebScraperTool webScraperTool, TextSummarizerTool textSummarizerTool, PdfReaderTool pdfReaderTool, WeatherTool weatherTool,
            ToolExecutionService toolExecutionService, McpToolProviderManager mcpToolProviderManager, IRestAgentStore restAgentStore,
            IRestWorkflowStore restWorkflowStore, IResourceClientLibrary resourceClientLibrary, IApiCallExecutor apiCallExecutor,
            IJsonSerialization jsonSerialization, IMemoryItemConverter memoryItemConverter) {
        this.calculatorTool = calculatorTool;
        this.dateTimeTool = dateTimeTool;
        this.webSearchTool = webSearchTool;
        this.dataFormatterTool = dataFormatterTool;
        this.webScraperTool = webScraperTool;
        this.textSummarizerTool = textSummarizerTool;
        this.pdfReaderTool = pdfReaderTool;
        this.weatherTool = weatherTool;
        this.toolExecutionService = toolExecutionService;
        this.mcpToolProviderManager = mcpToolProviderManager;
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.apiCallExecutor = apiCallExecutor;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
    }

    /**
     * Result of an agent execution.
     *
     * @param response
     *            the final LLM text response
     * @param trace
     *            list of tool call/result trace entries for debugging
     */
    record ExecutionResult(String response, List<Map<String, Object>> trace) {
    }

    /**
     * Collect enabled tools, append tool instructions to system message, and
     * execute the tool-calling loop.
     *
     * @return null if no tools are enabled (caller should use legacy mode),
     *         otherwise the execution result
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, LlmConfiguration.Task task,
            IConversationMemory memory) throws LifecycleException {

        // Collect enabled built-in tools
        List<Object> enabledTools = collectEnabledTools(task);

        // Discover MCP server tools (if configured)
        McpToolProviderManager.McpToolsResult mcpTools = null;
        List<McpServerConfig> mcpServers = task.getMcpServers();
        if (mcpServers != null && !mcpServers.isEmpty()) {
            mcpTools = mcpToolProviderManager.discoverTools(mcpServers);
        }
        boolean hasMcpTools = mcpTools != null && !mcpTools.toolSpecs().isEmpty();

        // Discover httpcall tools from workflow (auto-discovery)
        boolean enableHttpCallTools = task.getEnableHttpCallTools() == null || task.getEnableHttpCallTools();
        HttpCallToolsResult httpCallTools = null;
        if (enableHttpCallTools) {
            httpCallTools = discoverHttpCallTools(memory);
        }
        boolean hasHttpCallTools = httpCallTools != null && !httpCallTools.toolSpecs().isEmpty();

        // No tools? Return null — caller should use legacy mode
        if (enabledTools.isEmpty() && !hasMcpTools && !hasHttpCallTools) {
            return null;
        }

        int totalTools = enabledTools.size() + (hasMcpTools ? mcpTools.toolSpecs().size() : 0)
                + (hasHttpCallTools ? httpCallTools.toolSpecs().size() : 0);
        LOGGER.info("Executing with " + totalTools + " enabled tools"
                + (hasHttpCallTools ? " (" + httpCallTools.toolSpecs().size() + " from workflow httpcalls)" : "")
                + (hasMcpTools ? " (" + mcpTools.toolSpecs().size() + " from MCP servers)" : ""));

        return executeWithTools(chatModel, systemMessage, chatMessages, enabledTools, mcpTools, httpCallTools, task, memory);
    }

    /**
     * Executes the tool-calling loop using direct ChatModel API.
     */
    private ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, List<Object> tools,
            McpToolProviderManager.McpToolsResult mcpTools, HttpCallToolsResult httpCallTools, LlmConfiguration.Task task, IConversationMemory memory)
            throws LifecycleException {

        // Build tool specifications and executors from built-in tool objects
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> toolExecutors = new HashMap<>();

        for (Object tool : tools) {
            // CDI proxies don't carry @Tool annotations — resolve to actual bean class
            Class<?> toolClass = tool.getClass();
            if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
                toolClass = toolClass.getSuperclass();
            }

            var specs = ToolSpecifications.toolSpecificationsFrom(toolClass);
            toolSpecs.addAll(specs);

            // Find methods annotated with @Tool and map them to executors
            for (java.lang.reflect.Method method : toolClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                    String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                    toolExecutors.put(toolName, new DefaultToolExecutor(tool, method));
                }
            }
        }

        // Merge MCP server tools (if any)
        if (mcpTools != null && !mcpTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(mcpTools.toolSpecs());
            toolExecutors.putAll(mcpTools.executors());
        }

        // Merge httpcall tools discovered from workflow (if any)
        if (httpCallTools != null && !httpCallTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(httpCallTools.toolSpecs());
            toolExecutors.putAll(httpCallTools.executors());
        }

        // Build message list with system message if provided
        List<ChatMessage> messages = new ArrayList<>();
        if (!isNullOrEmpty(systemMessage)) {
            messages.add(SystemMessage.from(systemMessage));
        }
        messages.addAll(chatMessages);

        // Trace for tool calls
        List<Map<String, Object>> trace = new ArrayList<>();

        // Read config fields for tool execution controls
        boolean enableRateLimiting = task.getEnableRateLimiting() != null ? task.getEnableRateLimiting() : true;
        boolean enableCaching = task.getEnableToolCaching() != null ? task.getEnableToolCaching() : true;
        boolean enableCostTracking = task.getEnableCostTracking() != null ? task.getEnableCostTracking() : true;
        int defaultRateLimit = task.getDefaultRateLimit() != null ? task.getDefaultRateLimit() : 100;
        Map<String, Integer> toolRateLimits = task.getToolRateLimits();
        Double maxBudget = task.getMaxBudgetPerConversation();
        String conversationId = memory.getConversationId();

        // Execute with retry logic — the tool execution loop
        String response = AgentExecutionHelper.executeWithRetry(() -> {
            List<ChatMessage> currentMessages = new ArrayList<>(messages);
            int maxIterations = 10;

            for (int i = 0; i < maxIterations; i++) {
                ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(currentMessages);

                if (!toolSpecs.isEmpty()) {
                    requestBuilder.toolSpecifications(toolSpecs);
                }

                ChatRequest chatRequest = requestBuilder.build();

                ChatResponse chatResponse = chatModel.chat(chatRequest);
                AiMessage aiMessage = chatResponse.aiMessage();
                currentMessages.add(aiMessage);

                if (aiMessage.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                        Map<String, Object> callStep = new HashMap<>();
                        callStep.put("type", "tool_call");
                        callStep.put("tool", toolRequest.name());
                        callStep.put("arguments", toolRequest.arguments());
                        trace.add(callStep);

                        // Check budget before executing tool
                        if (maxBudget != null && conversationId != null
                                && !toolExecutionService.getCostTracker().isWithinBudget(conversationId, maxBudget)) {
                            String budgetError = "Budget exceeded for conversation " + conversationId;
                            LOGGER.warn(budgetError);

                            Map<String, Object> budgetStep = new HashMap<>();
                            budgetStep.put("type", "tool_error");
                            budgetStep.put("tool", toolRequest.name());
                            budgetStep.put("error", budgetError);
                            trace.add(budgetStep);

                            currentMessages.add(ToolExecutionResultMessage.from(toolRequest, "Error: " + budgetError));
                            continue;
                        }

                        // Execute through ToolExecutionService for rate limiting, caching, cost
                        // tracking
                        ToolExecutor executor = toolExecutors.get(toolRequest.name());
                        String toolResult;
                        if (executor != null) {
                            int rateLimit = (toolRateLimits != null && toolRateLimits.containsKey(toolRequest.name()))
                                    ? toolRateLimits.get(toolRequest.name())
                                    : defaultRateLimit;

                            toolResult = toolExecutionService.executeToolWrapped(toolRequest.name(), toolRequest.arguments(), conversationId,
                                    () -> executor.execute(toolRequest, null), enableRateLimiting, enableCaching, enableCostTracking, rateLimit);
                        } else {
                            toolResult = "Error: Tool '" + toolRequest.name() + "' not found";
                        }

                        Map<String, Object> resultStep = new HashMap<>();
                        resultStep.put("type", "tool_result");
                        resultStep.put("tool", toolRequest.name());
                        resultStep.put("result", toolResult);
                        trace.add(resultStep);

                        currentMessages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
                    }
                } else {
                    return aiMessage.text();
                }
            }

            AiMessage lastMessage = (AiMessage) currentMessages.get(currentMessages.size() - 1);
            return lastMessage.text() != null ? lastMessage.text() : "Max tool iterations reached";
        }, task, "Agent execution");

        return new ExecutionResult(response, trace);
    }

    /**
     * Collects enabled built-in tools based on task configuration.
     */
    List<Object> collectEnabledTools(LlmConfiguration.Task task) {
        List<Object> tools = new ArrayList<>();

        if (task.getEnableBuiltInTools() == null || !task.getEnableBuiltInTools()) {
            return tools;
        }

        List<String> whitelist = task.getBuiltInToolsWhitelist();

        if (whitelist != null && !whitelist.isEmpty()) {
            // Only add tools that are explicitly listed in the whitelist
            if (whitelist.contains("calculator"))
                tools.add(calculatorTool);
            if (whitelist.contains("datetime"))
                tools.add(dateTimeTool);
            if (whitelist.contains("websearch"))
                tools.add(webSearchTool);
            if (whitelist.contains("dataformatter"))
                tools.add(dataFormatterTool);
            if (whitelist.contains("webscraper"))
                tools.add(webScraperTool);
            if (whitelist.contains("textsummarizer"))
                tools.add(textSummarizerTool);
            if (whitelist.contains("pdfreader"))
                tools.add(pdfReaderTool);
            if (whitelist.contains("weather"))
                tools.add(weatherTool);
        } else {
            // No whitelist — add all built-in tools
            tools.add(calculatorTool);
            tools.add(dateTimeTool);
            tools.add(webSearchTool);
            tools.add(dataFormatterTool);
            tools.add(webScraperTool);
            tools.add(textSummarizerTool);
            tools.add(pdfReaderTool);
            tools.add(weatherTool);
        }

        LOGGER.info("Enabled " + tools.size() + " built-in tools for agent");
        return tools;
    }

    // --- Httpcall auto-discovery from workflow ---

    /**
     * Result of httpcall tool discovery.
     */
    record HttpCallToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
    }

    /**
     * Holder for an ApiCall and its parent configuration's target server URL.
     */
    private record HttpCallToolEntry(ApiCall apiCall, String targetServerUrl) {
    }

    /**
     * Discovers httpcall configurations from the workflow and creates
     * ToolSpecification + ToolExecutor for each ApiCall.
     * <p>
     * Traverses: memory → agentId/version → AgentConfiguration → workflows →
     * WorkflowConfiguration → filter httpcall steps → load ApiCallsConfiguration →
     * create tools from each ApiCall.
     */
    HttpCallToolsResult discoverHttpCallTools(IConversationMemory memory) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();

        try {
            // Load the agent configuration
            String agentId = memory.getAgentId();
            Integer agentVersion = memory.getAgentVersion();
            if (agentId == null || agentVersion == null) {
                LOGGER.debug("No agent context in memory — skipping httpcall tool discovery");
                return new HttpCallToolsResult(toolSpecs, executors);
            }
            LOGGER.info("Discovering httpcall tools for agent: " + agentId + " v" + agentVersion);

            // Use direct store read — ResourceClientLibrary doesn't map agent/workflow URIs
            AgentConfiguration agentConfig = restAgentStore.readAgent(agentId, agentVersion);

            if (agentConfig == null || agentConfig.getWorkflows() == null || agentConfig.getWorkflows().isEmpty()) {
                LOGGER.debug("No agent config or workflows found — skipping httpcall tool discovery");
                return new HttpCallToolsResult(toolSpecs, executors);
            }

            // Load each workflow and discover httpcall extensions
            List<HttpCallToolEntry> entries = new ArrayList<>();
            for (URI workflowUri : agentConfig.getWorkflows()) {
                // Parse id and version from workflow URI:
                // eddi://ai.labs.workflow/workflowstore/workflows/{id}?version={v}
                String workflowPath = workflowUri.getPath();
                if (workflowPath == null) {
                    LOGGER.warn("Workflow URI has no path: " + workflowUri);
                    continue;
                }
                String workflowId = workflowPath.substring(workflowPath.lastIndexOf('/') + 1);
                String workflowQuery = workflowUri.getQuery();
                if (workflowQuery == null || !workflowQuery.contains("version=")) {
                    LOGGER.warn("Workflow URI has no version query: " + workflowUri);
                    continue;
                }
                int workflowVersion = Integer.parseInt(workflowQuery.replaceAll(".*version=(\\d+).*", "$1"));

                WorkflowConfiguration workflowConfig = restWorkflowStore.readWorkflow(workflowId, workflowVersion);
                for (var step : workflowConfig.getWorkflowSteps()) {
                    if (step.getType() != null && HTTPCALLS_TYPE.equals(step.getType().toString())) {
                        String uri = (String) step.getConfig().get("uri");
                        if (uri != null) {
                            try {
                                ApiCallsConfiguration httpCallsConfig = resourceClientLibrary.getResource(URI.create(uri),
                                        ApiCallsConfiguration.class);
                                for (ApiCall apiCall : httpCallsConfig.getHttpCalls()) {
                                    entries.add(new HttpCallToolEntry(apiCall, httpCallsConfig.getTargetServerUrl()));
                                }
                            } catch (ServiceException e) {
                                LOGGER.warn("Failed to load httpcall config: " + uri, e);
                            }
                        }
                    }
                }
            }

            // Create ToolSpecification + ToolExecutor for each ApiCall
            for (HttpCallToolEntry entry : entries) {
                ApiCall apiCall = entry.apiCall();
                String targetServerUrl = entry.targetServerUrl();

                if (apiCall.getName() == null || apiCall.getName().isBlank()) {
                    continue;
                }

                ToolSpecification.Builder specBuilder = ToolSpecification.builder().name(apiCall.getName())
                        .description(apiCall.getDescription() != null ? apiCall.getDescription() : "Execute " + apiCall.getName());

                // Add parameters if defined
                if (apiCall.getParameters() != null && !apiCall.getParameters().isEmpty()) {
                    var schemaBuilder = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder();
                    for (var param : apiCall.getParameters().entrySet()) {
                        schemaBuilder.addStringProperty(param.getKey(), param.getValue() != null ? param.getValue() : param.getKey());
                    }
                    schemaBuilder.required(new ArrayList<>(apiCall.getParameters().keySet()));
                    specBuilder.parameters(schemaBuilder.build());
                }

                toolSpecs.add(specBuilder.build());

                // Create executor that uses real conversation memory
                executors.put(apiCall.getName(), (toolRequest, memoryId) -> {
                    try {
                        Map<String, Object> templateData = memoryItemConverter.convert(memory);

                        // Merge LLM-provided arguments into template data
                        if (toolRequest.arguments() != null && !toolRequest.arguments().isBlank()) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> args = jsonSerialization.deserialize(toolRequest.arguments(), Map.class);
                                templateData.putAll(args);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to parse tool arguments: " + toolRequest.arguments(), e);
                            }
                        }

                        Map<String, Object> result = apiCallExecutor.execute(apiCall, memory, templateData, targetServerUrl);

                        String serialized = jsonSerialization.serialize(result);
                        LOGGER.info("Httpcall tool '" + apiCall.getName() + "' result: keys=" + result.keySet() + " size=" + serialized.length());
                        return serialized;
                    } catch (Exception e) {
                        LOGGER.error("Error executing httpcall tool '" + apiCall.getName() + "'", e);
                        String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown error";
                        return "{\"error\": \"" + errorMsg + "\"}";
                    }
                });
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " httpcall tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover httpcall tools from workflow", e);
        }

        return new HttpCallToolsResult(toolSpecs, executors);
    }
}
