package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration.McpServerConfig;
import ai.labs.eddi.modules.langchain.tools.EddiToolBridge;
import ai.labs.eddi.modules.langchain.tools.ToolExecutionService;
import ai.labs.eddi.modules.langchain.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // Built-in tools
    private final CalculatorTool calculatorTool;
    private final DateTimeTool dateTimeTool;
    private final WebSearchTool webSearchTool;
    private final DataFormatterTool dataFormatterTool;
    private final WebScraperTool webScraperTool;
    private final TextSummarizerTool textSummarizerTool;
    private final PdfReaderTool pdfReaderTool;
    private final WeatherTool weatherTool;
    private final EddiToolBridge eddiToolBridge;
    private final ToolExecutionService toolExecutionService;
    private final McpToolProviderManager mcpToolProviderManager;

    AgentOrchestrator(CalculatorTool calculatorTool,
            DateTimeTool dateTimeTool,
            WebSearchTool webSearchTool,
            DataFormatterTool dataFormatterTool,
            WebScraperTool webScraperTool,
            TextSummarizerTool textSummarizerTool,
            PdfReaderTool pdfReaderTool,
            WeatherTool weatherTool,
            EddiToolBridge eddiToolBridge,
            ToolExecutionService toolExecutionService,
            McpToolProviderManager mcpToolProviderManager) {
        this.calculatorTool = calculatorTool;
        this.dateTimeTool = dateTimeTool;
        this.webSearchTool = webSearchTool;
        this.dataFormatterTool = dataFormatterTool;
        this.webScraperTool = webScraperTool;
        this.textSummarizerTool = textSummarizerTool;
        this.pdfReaderTool = pdfReaderTool;
        this.weatherTool = weatherTool;
        this.eddiToolBridge = eddiToolBridge;
        this.toolExecutionService = toolExecutionService;
        this.mcpToolProviderManager = mcpToolProviderManager;
    }

    /**
     * Result of an agent execution.
     *
     * @param response the final LLM text response
     * @param trace    list of tool call/result trace entries for debugging
     */
    record ExecutionResult(String response, List<Map<String, Object>> trace) {
    }

    /**
     * Collect enabled tools, append tool instructions to system message,
     * and execute the tool-calling loop.
     *
     * @return null if no tools are enabled (caller should use legacy mode),
     *         otherwise the execution result
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel,
            String systemMessage,
            List<ChatMessage> chatMessages,
            LangChainConfiguration.Task task,
            IConversationMemory memory) throws LifecycleException {

        // Collect enabled built-in tools
        List<Object> enabledTools = collectEnabledTools(task);

        // Add custom tools (EddiToolBridge) if configured
        if (task.getTools() != null && !task.getTools().isEmpty()) {
            enabledTools.add(eddiToolBridge);
        }

        // Discover MCP server tools (if configured)
        McpToolProviderManager.McpToolsResult mcpTools = null;
        List<McpServerConfig> mcpServers = task.getMcpServers();
        if (mcpServers != null && !mcpServers.isEmpty()) {
            mcpTools = mcpToolProviderManager.discoverTools(mcpServers);
        }
        boolean hasMcpTools = mcpTools != null && !mcpTools.toolSpecs().isEmpty();

        // No tools? Return null — caller should use legacy mode
        if (enabledTools.isEmpty() && !hasMcpTools) {
            return null;
        }

        // Append tool instructions to system message if custom tools are configured
        String effectiveSystemMessage = systemMessage;
        if (task.getTools() != null && !task.getTools().isEmpty()) {
            StringBuilder toolInstructions = new StringBuilder(
                    "\n\nYou have access to the following external tools via the 'executeHttpCall' function:\n");
            for (String toolUri : task.getTools()) {
                toolInstructions.append("- ").append(toolUri).append("\n");
            }
            toolInstructions
                    .append("When using 'executeHttpCall', pass the exact URI as the 'httpCallUri' argument.\n");

            if (isNullOrEmpty(effectiveSystemMessage)) {
                effectiveSystemMessage = toolInstructions.toString();
            } else {
                effectiveSystemMessage += toolInstructions.toString();
            }
        }

        int totalTools = enabledTools.size() + (mcpTools != null ? mcpTools.toolSpecs().size() : 0);
        LOGGER.info("Executing with " + totalTools + " enabled tools" +
                (mcpTools != null && !mcpTools.toolSpecs().isEmpty()
                        ? " (" + mcpTools.toolSpecs().size() + " from MCP servers)" : ""));
        return executeWithTools(chatModel, effectiveSystemMessage, chatMessages, enabledTools, mcpTools, task, memory);
    }

    /**
     * Executes the tool-calling loop using direct ChatModel API.
     */
    private ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage,
            List<ChatMessage> chatMessages, List<Object> tools,
            McpToolProviderManager.McpToolsResult mcpTools,
            LangChainConfiguration.Task task, IConversationMemory memory)
            throws LifecycleException {

        // Build tool specifications and executors from built-in tool objects
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> toolExecutors = new HashMap<>();

        for (Object tool : tools) {
            var specs = ToolSpecifications.toolSpecificationsFrom(tool);
            toolSpecs.addAll(specs);

            // Find methods annotated with @Tool and map them to executors
            for (java.lang.reflect.Method method : tool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    dev.langchain4j.agent.tool.Tool toolAnnotation = method
                            .getAnnotation(dev.langchain4j.agent.tool.Tool.class);
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
                ChatRequest.Builder requestBuilder = ChatRequest.builder()
                        .messages(currentMessages);

                if (!toolSpecs.isEmpty()) {
                    requestBuilder.parameters(ChatRequestParameters.builder()
                            .toolSpecifications(toolSpecs)
                            .build());
                }

                ChatResponse chatResponse = chatModel.chat(requestBuilder.build());
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
                        if (maxBudget != null && conversationId != null &&
                                !toolExecutionService.getCostTracker().isWithinBudget(conversationId, maxBudget)) {
                            String budgetError = "Budget exceeded for conversation " + conversationId;
                            LOGGER.warn(budgetError);

                            Map<String, Object> budgetStep = new HashMap<>();
                            budgetStep.put("type", "tool_error");
                            budgetStep.put("tool", toolRequest.name());
                            budgetStep.put("error", budgetError);
                            trace.add(budgetStep);

                            currentMessages.add(ToolExecutionResultMessage.from(toolRequest,
                                    "Error: " + budgetError));
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

                            toolResult = toolExecutionService.executeToolWrapped(
                                    toolRequest.name(),
                                    toolRequest.arguments(),
                                    conversationId,
                                    () -> executor.execute(toolRequest, null),
                                    enableRateLimiting,
                                    enableCaching,
                                    enableCostTracking,
                                    rateLimit);
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
    List<Object> collectEnabledTools(LangChainConfiguration.Task task) {
        List<Object> tools = new ArrayList<>();

        if (task.getEnableBuiltInTools() == null || !task.getEnableBuiltInTools()) {
            return tools;
        }

        List<String> whitelist = task.getBuiltInToolsWhitelist();

        if (whitelist != null && !whitelist.isEmpty()) {
            // Only add tools that are explicitly listed in the whitelist
            if (whitelist.contains("calculator")) tools.add(calculatorTool);
            if (whitelist.contains("datetime")) tools.add(dateTimeTool);
            if (whitelist.contains("websearch")) tools.add(webSearchTool);
            if (whitelist.contains("dataformatter")) tools.add(dataFormatterTool);
            if (whitelist.contains("webscraper")) tools.add(webScraperTool);
            if (whitelist.contains("textsummarizer")) tools.add(textSummarizerTool);
            if (whitelist.contains("pdfreader")) tools.add(pdfReaderTool);
            if (whitelist.contains("weather")) tools.add(weatherTool);
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
}
