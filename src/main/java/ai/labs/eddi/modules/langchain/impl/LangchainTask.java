package ai.labs.eddi.modules.langchain.impl;


import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.httpcalls.impl.PrePostUtils;
import ai.labs.eddi.modules.langchain.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration.Task;
import ai.labs.eddi.modules.langchain.tools.EddiToolBridge;
import ai.labs.eddi.modules.langchain.tools.ToolExecutionService;
import ai.labs.eddi.modules.langchain.tools.impl.*;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class LangchainTask implements ILifecycleTask {
    public static final String ID = "ai.labs.langchain";

    private static final String KEY_URI = "uri";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_LANGCHAIN = "langchain";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";
    private static final String KEY_INCLUDE_FIRST_BOT_MESSAGE = "includeFirstBotMessage";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    private static final String KEY_ADD_TO_OUTPUT = "addToOutput";
    private static final String MATCH_ALL_OPERATOR = "*";

    static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    static final String LANGCHAIN_OUTPUT_IDENTIFIER = MEMORY_OUTPUT_IDENTIFIER + ":text:langchain";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;
    private final PrePostUtils prePostUtils;
    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders;

    // Built-in tools for agent mode
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

    private final Map<ModelCacheKey, ChatModel> modelCache = new ConcurrentHashMap<>(1);

    private static final Logger LOGGER = Logger.getLogger(LangchainTask.class);

    @Inject
    public LangchainTask(IResourceClientLibrary resourceClientLibrary,
                         IDataFactory dataFactory,
                         IMemoryItemConverter memoryItemConverter,
                         ITemplatingEngine templatingEngine,
                         IJsonSerialization jsonSerialization,
                         PrePostUtils prePostUtils,
                         Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders,
                         CalculatorTool calculatorTool,
                         DateTimeTool dateTimeTool,
                         WebSearchTool webSearchTool,
                         DataFormatterTool dataFormatterTool,
                         WebScraperTool webScraperTool,
                         TextSummarizerTool textSummarizerTool,
                         PdfReaderTool pdfReaderTool,
                         WeatherTool weatherTool,
                         EddiToolBridge eddiToolBridge,
                         ToolExecutionService toolExecutionService) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.prePostUtils = prePostUtils;
        this.languageModelApiConnectorBuilders = languageModelApiConnectorBuilders;
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
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return KEY_LANGCHAIN;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        final var langChainConfig = (LangChainConfiguration) component;

        try {
            IWritableConversationStep currentStep = memory.getCurrentStep();
            IData<List<String>> latestData = currentStep.getLatestData(KEY_ACTIONS);
            if (latestData == null) {
                return;
            }

            var templateDataObjects = memoryItemConverter.convert(memory);
            var actions = latestData.getResult();
            if (actions == null) {
                return;
            }

            for (var task : langChainConfig.tasks()) {
                if (task.getActions().contains(MATCH_ALL_OPERATOR) ||
                        task.getActions().stream().anyMatch(actions::contains)) {
                    executeTask(memory, task, currentStep, templateDataObjects);
                }
            }

        } catch (ITemplatingEngine.TemplateEngineException | UnsupportedLangchainTaskException | IOException | LifecycleException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * Execute task - handles both legacy mode (simple chat) and agent mode (with tools)
     */
    private void executeTask(IConversationMemory memory, Task task,
                            IWritableConversationStep currentStep,
                            Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, UnsupportedLangchainTaskException, IOException, LifecycleException {

        var processedParams = runTemplateEngineOnParams(task.getParameters(), templateDataObjects);

        // Build system message
        String systemMessage = processedParams.get(KEY_SYSTEM_MESSAGE);
        if (isNullOrEmpty(systemMessage)) {
            systemMessage = "";
        }

        // Build conversation history
        int logSizeLimit = task.getConversationHistoryLimit() != null ?
                task.getConversationHistoryLimit() : -1;

        // Override with legacy parameter if present
        if (!isNullOrEmpty(processedParams.get(KEY_LOG_SIZE_LIMIT))) {
            logSizeLimit = Integer.parseInt(processedParams.get(KEY_LOG_SIZE_LIMIT));
        }

        boolean includeFirstBotMessage = true;
        if (!isNullOrEmpty(processedParams.get(KEY_INCLUDE_FIRST_BOT_MESSAGE))) {
            includeFirstBotMessage = Boolean.parseBoolean(processedParams.get(KEY_INCLUDE_FIRST_BOT_MESSAGE));
        }

        var chatMessages = new ArrayList<>(new ConversationLogGenerator(memory).
                generate(logSizeLimit, includeFirstBotMessage)
                .getMessages()
                .stream()
                .map(this::convertMessage)
                .toList());

        if (!isNullOrEmpty(processedParams.get(KEY_PROMPT))) {
            // if there is a prompt defined, we replace the last user input with it
            if (!chatMessages.isEmpty()) {
                chatMessages.removeLast();
            }
            chatMessages.add(UserMessage.from(processedParams.get(KEY_PROMPT)));
        }

        // Build full message list
        var messages = new LinkedList<ChatMessage>();
        if (!isNullOrEmpty(systemMessage)) {
            messages.add(new SystemMessage(systemMessage));
        }
        messages.addAll(chatMessages);

        if (messages.isEmpty()) {
            return;
        }

        // Collect enabled tools (empty list if not in agent mode)
        List<Object> enabledTools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool);

        // Add custom tools (EddiToolBridge) if configured
        if (task.getTools() != null && !task.getTools().isEmpty()) {
            enabledTools.add(eddiToolBridge);

            // Append tool instructions to system message
            StringBuilder toolInstructions = new StringBuilder("\n\nYou have access to the following external tools via the 'executeHttpCall' function:\n");
            for (String toolUri : task.getTools()) {
                toolInstructions.append("- ").append(toolUri).append("\n");
            }
            toolInstructions.append("When using 'executeHttpCall', pass the exact URI as the 'httpCallUri' argument.\n");

            if (isNullOrEmpty(systemMessage)) {
                systemMessage = toolInstructions.toString();
            } else {
                systemMessage += toolInstructions.toString();
            }
        }

        var chatModel = getChatModel(task.getType(), filterParams(processedParams));
        prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.getPreRequest());

        // Execute with or without tools
        String responseContent;
        Map<String, Object> responseMetadata = new HashMap<>();
        List<Map<String, Object>> toolTrace = new ArrayList<>();

        if (!enabledTools.isEmpty()) {
            // Agent mode: Execute with tools using AiServices
            LOGGER.info("Executing with " + enabledTools.size() + " enabled tools");
            var executionResult = executeWithTools(chatModel, systemMessage, chatMessages, enabledTools, task, memory);
            responseContent = executionResult.response();
            toolTrace = executionResult.trace();
        } else {
            // Legacy mode: Simple chat without tools
            LOGGER.debug("Executing without tools (legacy mode)");
            var messageResponse = AgentExecutionHelper.executeChatWithRetry(chatModel, messages, task);
            responseContent = messageResponse.aiMessage().text();
            // Store metadata if available
            var metadata = messageResponse.metadata();
            if (metadata != null) {
                if (metadata.finishReason() != null) {
                    responseMetadata.put("finishReason", metadata.finishReason().toString());
                }
                if (metadata.tokenUsage() != null) {
                    responseMetadata.put("tokenUsage", Map.of(
                            "inputTokens", metadata.tokenUsage().inputTokenCount(),
                            "outputTokens", metadata.tokenUsage().outputTokenCount(),
                            "totalTokens", metadata.tokenUsage().totalTokenCount()
                    ));
                }
            }
        }

        // Store metadata if configured
        var responseMetadataObjectName = task.getResponseMetadataObjectName();
        if (!isNullOrEmpty(responseMetadataObjectName)) {
            templateDataObjects.put(responseMetadataObjectName, responseMetadata);
            prePostUtils.createMemoryEntry(currentStep, responseMetadata, responseMetadataObjectName, KEY_LANGCHAIN);
        }

        // Store response content
        var responseObjectName = task.getResponseObjectName();
        if (isNullOrEmpty(responseObjectName)) {
            responseObjectName = task.getId();
        }

        if (Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT))) {
            var contentAsObject = jsonSerialization.deserialize(responseContent, Map.class);
            templateDataObjects.put(responseObjectName, contentAsObject);
        } else {
            templateDataObjects.put(responseObjectName, responseContent);
        }

        var langchainData = dataFactory.createData(KEY_LANGCHAIN + ":" + task.getType() + ":" + task.getId(), responseContent);
        currentStep.storeData(langchainData);

        // Store tool trace if available
        if (!toolTrace.isEmpty()) {
            var traceData = dataFactory.createData(KEY_LANGCHAIN + ":trace:" + task.getType() + ":" + task.getId(), toolTrace);
            currentStep.storeData(traceData);
        }

        // Add to output if configured (or if in agent mode with tools)
        boolean shouldAddToOutput = !enabledTools.isEmpty() || // Always output in agent mode with tools
                (!isNullOrEmpty(processedParams.get(KEY_ADD_TO_OUTPUT)) &&
                 Boolean.parseBoolean(processedParams.get(KEY_ADD_TO_OUTPUT)));

        if (shouldAddToOutput) {
            var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), responseContent);
            currentStep.storeData(outputData);
            var outputItem = new TextOutputItem(responseContent, 0);
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
        }

        prePostUtils.runPostResponse(memory, task.getPostResponse(), templateDataObjects, 200, false);
    }

    /**
     * Executes chat with tool support using direct ChatModel API with tool execution loop.
     * This avoids using AiServices which triggers Quarkus-LangChain4j CDI interception.
     */
    private ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage,
                                   List<ChatMessage> chatMessages, List<Object> tools,
                                   Task task, IConversationMemory memory) throws LifecycleException {

        // Build tool specifications and executors from tool objects
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> toolExecutors = new HashMap<>();

        for (Object tool : tools) {
            var specs = ToolSpecifications.toolSpecificationsFrom(tool);
            toolSpecs.addAll(specs);
            
            // Find methods annotated with @Tool and map them to executors
            for (java.lang.reflect.Method method : tool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                    // Tool name defaults to method name if not specified
                    String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                    toolExecutors.put(toolName, new DefaultToolExecutor(tool, method));
                }
            }
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

        // Execute with retry logic - the tool execution loop
        String response = AgentExecutionHelper.executeWithRetry(() -> {
            List<ChatMessage> currentMessages = new ArrayList<>(messages);
            int maxIterations = 10; // Prevent infinite loops

            for (int i = 0; i < maxIterations; i++) {
                // Build chat request with tools
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

                // Check if there are tool execution requests
                if (aiMessage.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                        // Record in trace
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

                        // Execute the tool through ToolExecutionService for rate limiting, caching, cost tracking
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
                                    rateLimit
                            );
                        } else {
                            toolResult = "Error: Tool '" + toolRequest.name() + "' not found";
                        }

                        // Record result in trace
                        Map<String, Object> resultStep = new HashMap<>();
                        resultStep.put("type", "tool_result");
                        resultStep.put("tool", toolRequest.name());
                        resultStep.put("result", toolResult);
                        trace.add(resultStep);

                        // Add tool result to messages
                        currentMessages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
                    }
                    // Continue the loop to send tool results back to the model
                } else {
                    // No tool calls, return the response
                    return aiMessage.text();
                }
            }

            // Max iterations reached
            AiMessage lastMessage = (AiMessage) currentMessages.get(currentMessages.size() - 1);
            return lastMessage.text() != null ? lastMessage.text() : "Max tool iterations reached";
        }, task, "Agent execution");

        return new ExecutionResult(response, trace);
    }

    private record ExecutionResult(String response, List<Map<String, Object>> trace) {}


    private HashMap<String, String> runTemplateEngineOnParams(Map<String, String> parameters,
                                                              Map<String, Object> templateDataObjects) {

        var processedParams = new HashMap<>(parameters);
        processedParams.forEach((key, value) -> {
            try {
                if (!isNullOrEmpty(value)) {
                    processedParams.put(key, templatingEngine.processTemplate(value, templateDataObjects));
                }
            } catch (ITemplatingEngine.TemplateEngineException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
            }
        });
        return processedParams;
    }

    private Map<String, String> filterParams(HashMap<String, String> processedParams) {
        //remove all props that are not directly configuring the langchain builders (for better caching)
        var returnMap = new HashMap<>(processedParams);
        returnMap.remove(KEY_INCLUDE_FIRST_BOT_MESSAGE);
        returnMap.remove(KEY_SYSTEM_MESSAGE);
        returnMap.remove(KEY_PROMPT);
        returnMap.remove(KEY_LOG_SIZE_LIMIT);
        returnMap.remove(KEY_ADD_TO_OUTPUT);
        returnMap.remove(KEY_CONVERT_TO_OBJECT);
        return returnMap;
    }

    private ChatModel getChatModel(String type, Map<String, String> parameters)
            throws UnsupportedLangchainTaskException {

        var cacheKey = new ModelCacheKey(type, parameters);

        if (modelCache.containsKey(cacheKey)) {
            return modelCache.get(cacheKey);
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLangchainTaskException(String.format("Type \"%s\" is not supported", type));
        }

        var model = languageModelApiConnectorBuilders.get(type).get().build(parameters);
        modelCache.put(cacheKey, model);

        return model;
    }

    private ChatMessage convertMessage(ConversationLog.ConversationPart eddiMessage) {
        return switch (eddiMessage.getRole().toLowerCase()) {
            case "user" -> {
                var contentList = new LinkedList<Content>();
                for (var content : eddiMessage.getContent()) {
                    switch (content.getType()) {
                        case text -> contentList.add(TextContent.from(content.getValue()));
                        case pdf -> contentList.add(PdfFileContent.from(content.getValue()));
                        case audio -> contentList.add(AudioContent.from(content.getValue()));
                        case video -> contentList.add(VideoContent.from(content.getValue()));
                    }
                }
                yield UserMessage.from(contentList);
            }
            case "assistant" -> AiMessage.from(joinMessages(eddiMessage));
            default -> SystemMessage.from(joinMessages(eddiMessage));
        };
    }

    private static String joinMessages(ConversationLog.ConversationPart eddiMessage) {
        return eddiMessage.getContent().stream().
                map(ConversationLog.ConversationPart.Content::getValue).collect(Collectors.joining(" "));
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        Object uriObj = configuration.get(KEY_URI);
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                return resourceClientLibrary.getResource(uri, LangChainConfiguration.class);
            } catch (ServiceException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new PackageConfigurationException(e.getLocalizedMessage(), e);
            }
        }

        throw new PackageConfigurationException("No resource URI has been defined! [LangChainConfiguration]");
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Lang Chain");

        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(KEY_URI, configValue);

        return extensionDescriptor;
    }

    private record ModelCacheKey(String type, Map<String, String> parameters) {
        private ModelCacheKey(String type, Map<String, String> parameters) {
            this.type = type;
            this.parameters = new HashMap<>(parameters);
        }
    }

    public static class UnsupportedLangchainTaskException extends Exception {
        public UnsupportedLangchainTaskException(String message) {
            super(message);
        }
    }
}
