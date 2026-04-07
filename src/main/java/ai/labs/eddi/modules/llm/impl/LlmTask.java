package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.ChatMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;
import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Lifecycle task for LLM interactions — supports both legacy chat and agent
 * (tool-calling) modes.
 * <p>
 * This class is a thin orchestrator that delegates to:
 * <ul>
 * <li>{@link ChatModelRegistry} — model creation, caching, and lookup</li>
 * <li>{@link ConversationHistoryBuilder} — memory → ChatMessage list
 * conversion</li>
 * <li>{@link LegacyChatExecutor} — simple chat mode (no tools)</li>
 * <li>{@link AgentOrchestrator} — tool-calling agent loop</li>
 * </ul>
 */
@ApplicationScoped
public class LlmTask implements ILifecycleTask {
    public static final String ID = "ai.labs.llm";

    private static final String KEY_URI = "uri";
    private static final String KEY_LANGCHAIN = "langchain";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";
    private static final String KEY_INCLUDE_FIRST_AGENT_MESSAGE = "includeFirstAgentMessage";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    private static final String KEY_ADD_TO_OUTPUT = "addToOutput";
    private static final String KEY_RESPONSE_SCHEMA = "responseSchema";
    private static final String HTTPCALLS_TYPE = "eddi://ai.labs.httpcalls";
    private static final String MATCH_ALL_OPERATOR = "*";

    static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    static final String LANGCHAIN_OUTPUT_IDENTIFIER = MEMORY_OUTPUT_IDENTIFIER + ":text:langchain";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;
    private final PrePostUtils prePostUtils;

    private final ChatModelRegistry chatModelRegistry;
    private final ConversationHistoryBuilder conversationHistoryBuilder;
    private final LegacyChatExecutor legacyChatExecutor;
    private final StreamingLegacyChatExecutor streamingLegacyChatExecutor;
    private final AgentOrchestrator agentOrchestrator;
    private final RagContextProvider ragContextProvider;
    private final TokenCounterFactory tokenCounterFactory;
    private final ConversationSummarizer conversationSummarizer;
    private final CounterweightService counterweightService;
    private final DeploymentContextService deploymentContextService;
    private final IdentityMaskingService identityMaskingService;

    // Retained for httpCall RAG discovery + execution (Phase 8c-0)
    private final IApiCallExecutor apiCallExecutor;
    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;

    private static final Logger LOGGER = Logger.getLogger(LlmTask.class);

    @Inject
    public LlmTask(IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory, IMemoryItemConverter memoryItemConverter,
            ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, PrePostUtils prePostUtils, ChatModelRegistry chatModelRegistry,
            CalculatorTool calculatorTool, DateTimeTool dateTimeTool, WebSearchTool webSearchTool, DataFormatterTool dataFormatterTool,
            WebScraperTool webScraperTool, TextSummarizerTool textSummarizerTool, PdfReaderTool pdfReaderTool, WeatherTool weatherTool,
            IApiCallExecutor apiCallExecutor, ToolExecutionService toolExecutionService, McpToolProviderManager mcpToolProviderManager,
            A2AToolProviderManager a2aToolProviderManager, IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore,
            RagContextProvider ragContextProvider, IUserMemoryStore userMemoryStore, TokenCounterFactory tokenCounterFactory,
            ConversationSummarizer conversationSummarizer, CounterweightService counterweightService,
            DeploymentContextService deploymentContextService, IdentityMaskingService identityMaskingService,
            ToolResponseTruncator toolResponseTruncator) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.prePostUtils = prePostUtils;

        this.chatModelRegistry = chatModelRegistry;
        this.conversationHistoryBuilder = new ConversationHistoryBuilder();
        this.legacyChatExecutor = new LegacyChatExecutor();
        this.streamingLegacyChatExecutor = new StreamingLegacyChatExecutor();
        this.agentOrchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool,
                textSummarizerTool, pdfReaderTool, weatherTool, toolExecutionService, mcpToolProviderManager, a2aToolProviderManager, restAgentStore,
                restWorkflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization, memoryItemConverter, userMemoryStore,
                toolResponseTruncator);
        this.ragContextProvider = ragContextProvider;
        this.tokenCounterFactory = tokenCounterFactory;
        this.apiCallExecutor = apiCallExecutor;
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.conversationSummarizer = conversationSummarizer;
        this.counterweightService = counterweightService;
        this.deploymentContextService = deploymentContextService;
        this.identityMaskingService = identityMaskingService;
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
        final var llmConfig = (LlmConfiguration) component;

        try {
            IWritableConversationStep currentStep = memory.getCurrentStep();
            IData<List<String>> latestData = currentStep.getLatestData(ACTIONS);
            if (latestData == null) {
                return;
            }

            var templateDataObjects = memoryItemConverter.convert(memory);
            var actions = latestData.getResult();
            if (actions == null) {
                return;
            }

            for (var task : llmConfig.tasks()) {
                if (task.getActions().contains(MATCH_ALL_OPERATOR) || task.getActions().stream().anyMatch(actions::contains)) {
                    executeTask(memory, task, currentStep, templateDataObjects);
                }
            }

        } catch (ITemplatingEngine.TemplateEngineException | ChatModelRegistry.UnsupportedLlmTaskException | IOException | LifecycleException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * Execute a single task — delegates to LegacyChatExecutor or AgentOrchestrator.
     */
    private void executeTask(IConversationMemory memory, Task task, IWritableConversationStep currentStep, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, ChatModelRegistry.UnsupportedLlmTaskException, IOException, LifecycleException {

        var processedParams = runTemplateEngineOnParams(task.getParameters(), templateDataObjects);

        // Parse history parameters
        String systemMessage = processedParams.getOrDefault(KEY_SYSTEM_MESSAGE, "");

        // === Behavioral Governance: Counterweight Injection ===
        // Priority: explicit task config > deployment environment auto-counterweight
        var counterweight = task.getCounterweight();
        String effectiveLevel = null;
        if (counterweight != null && counterweight.isEnabled()) {
            // Explicit config: use custom instructions or level
            if (counterweight.getInstructions() != null && !counterweight.getInstructions().isEmpty()) {
                effectiveLevel = "custom";
            } else {
                effectiveLevel = counterweight.getLevel();
            }
        }
        if (effectiveLevel == null || "normal".equalsIgnoreCase(effectiveLevel)) {
            // No explicit counterweight — check deployment environment fallback
            effectiveLevel = deploymentContextService.getAutoCounterweightLevel();
        }
        if (effectiveLevel != null && !"normal".equalsIgnoreCase(effectiveLevel)) {
            // Build a synthetic config for the resolved level (or use task config for
            // custom instructions)
            var effectiveConfig = counterweight != null && counterweight.isEnabled() ? counterweight : new LlmConfiguration.CounterweightConfig();
            if (!(counterweight != null && counterweight.isEnabled())) {
                effectiveConfig.setEnabled(true);
                effectiveConfig.setLevel(effectiveLevel);
            }
            String cwInstructions = counterweightService.resolveInstructions(effectiveConfig);
            if (!cwInstructions.isEmpty()) {
                systemMessage += "\n\n## BEHAVIORAL GOVERNANCE\n" + cwInstructions;
                var cwTraceData = dataFactory.createData(KEY_LANGCHAIN + ":counterweight:level", effectiveLevel);
                currentStep.storeData(cwTraceData);
            }
        }

        // === Identity Masking Injection ===
        var identityMasking = task.getIdentityMasking();
        if (identityMasking != null && identityMasking.isEnabled()) {
            String maskingInstructions = identityMaskingService.resolveInstructions(identityMasking);
            if (!maskingInstructions.isEmpty()) {
                systemMessage += "\n\n## IDENTITY\n" + maskingInstructions;
                var maskTraceData = dataFactory.createData(KEY_LANGCHAIN + ":identity:displayName",
                        identityMasking.getDisplayName() != null ? identityMasking.getDisplayName() : "custom");
                currentStep.storeData(maskTraceData);
            }
        }

        int logSizeLimit = task.getConversationHistoryLimit() != null ? task.getConversationHistoryLimit() : -1;
        if (!isNullOrEmpty(processedParams.get(KEY_LOG_SIZE_LIMIT))) {
            logSizeLimit = Integer.parseInt(processedParams.get(KEY_LOG_SIZE_LIMIT));
        }
        boolean includeFirstAgentMessage = isNullOrEmpty(processedParams.get(KEY_INCLUDE_FIRST_AGENT_MESSAGE))
                || Boolean.parseBoolean(processedParams.get(KEY_INCLUDE_FIRST_AGENT_MESSAGE));

        // === RAG Context Injection ===
        String userInput = extractUserInput(memory);
        String taskId = task.getId() != null ? task.getId() : "default";

        // Phase 8c-0: Zero-infrastructure RAG via named httpCall
        String httpCallRag = task.getHttpCallRag();
        if (!isNullOrEmpty(httpCallRag) && userInput != null) {
            try {
                String httpCallContext = executeHttpCallRag(memory, httpCallRag, userInput, templateDataObjects);
                if (httpCallContext != null) {
                    systemMessage += "\n\n## Search Results:\n" + httpCallContext;
                    LOGGER.infof("httpCall RAG context injected for task '%s': %d chars", taskId, httpCallContext.length());
                    var traceData = dataFactory.createData("rag:httpcall:trace:" + taskId,
                            Map.of("httpCall", httpCallRag, "contextLength", httpCallContext.length()));
                    currentStep.storeData(traceData);
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "httpCall RAG failed for '%s': %s", httpCallRag, e.getMessage());
            }
        }

        // Vector store RAG: retrieve from knowledge bases in the workflow
        if (userInput != null) {
            try {
                String ragContext = ragContextProvider.retrieveContext(memory, task, userInput);
                if (ragContext != null) {
                    systemMessage += "\n\n## Relevant Context:\n" + ragContext;
                    LOGGER.infof("RAG context injected for task '%s': %d chars", taskId, ragContext.length());
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "RAG context retrieval failed for task '%s': %s", taskId, e.getMessage());
            }
        }

        // When structured JSON output is expected, reinforce the format instruction.
        // If a responseSchema is provided, include it explicitly so the LLM knows the
        // exact shape.
        if (Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT))) {
            String schema = processedParams.get(KEY_RESPONSE_SCHEMA);
            if (!isNullOrEmpty(schema)) {
                systemMessage += "\n\n## RESPONSE FORMAT (MANDATORY)\n"
                        + "You MUST respond with a single valid JSON object matching this exact schema:\n" + "```json\n" + schema + "\n```\n"
                        + "Do NOT include ANY text before or after the JSON. " + "Do NOT wrap in markdown code fences. "
                        + "Output ONLY the raw JSON object.";
            } else {
                systemMessage += "\n\n## RESPONSE FORMAT (MANDATORY)\n" + "You MUST respond with a single valid JSON object. "
                        + "Do NOT include ANY text before or after the JSON. " + "Do NOT wrap in markdown code fences. "
                        + "Output ONLY the raw JSON object starting with '{'.";
            }
        }

        // Build conversation messages — token-aware or step-count
        // If rolling summary is active, inject summary prefix and skip summarized steps
        Integer maxContextTokens = task.getMaxContextTokens();
        int anchorFirstSteps = task.getAnchorFirstSteps() != null ? task.getAnchorFirstSteps() : 2;

        String summaryPrefix = null;
        int skipSteps = 0;
        var summaryConfig = task.getConversationSummary();
        if (summaryConfig != null && summaryConfig.isEnabled()) {
            String existingSummary = ConversationSummarizer.readSummary(memory);
            if (existingSummary != null) {
                int throughStep = ConversationSummarizer.readSummaryThroughStep(memory);
                summaryPrefix = "## CONVERSATION SUMMARY (turns 1-" + throughStep + " condensed)\n" + existingSummary + "\n\n"
                        + "[You can use the recallConversationDetail tool to access full details from these summarized turns.]";
                skipSteps = throughStep;
                LOGGER.infof("[SUMMARY] Using rolling summary for task '%s': covers turns 1-%d, recent window from step %d", taskId, throughStep,
                        throughStep + 1);
            }
        }

        List<ChatMessage> messages;
        if (maxContextTokens != null && maxContextTokens > 0) {
            // Resolve model name from provider-specific parameter keys
            String resolvedModelName = resolveModelName(processedParams);
            var estimator = tokenCounterFactory.getEstimator(task.getType(), resolvedModelName);
            messages = conversationHistoryBuilder.buildTokenAwareMessages(memory, systemMessage, processedParams.get(KEY_PROMPT), maxContextTokens,
                    anchorFirstSteps, includeFirstAgentMessage, estimator, summaryPrefix, skipSteps);
        } else {
            // Legacy step-count windowing
            messages = conversationHistoryBuilder.buildMessages(memory, systemMessage, processedParams.get(KEY_PROMPT), logSizeLimit,
                    includeFirstAgentMessage, summaryPrefix, skipSteps);
        }
        if (messages.isEmpty()) {
            return;
        }

        var chatModel = chatModelRegistry.getOrCreate(task.getType(), processedParams);
        prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.getPreRequest());

        // Detect streaming mode — event sink is set when SSE endpoint is used
        ConversationEventSink eventSink = memory.getEventSink();

        // When addToOutput is explicitly "false" (structured JSON with postResponse),
        // do NOT stream or add the raw response — the postResponse will generate proper
        // output.
        boolean addToOutputExplicitlyFalse = "false".equalsIgnoreCase(processedParams.get(KEY_ADD_TO_OUTPUT));

        // When convertToObject is true, use native JSON response format on the API
        // level
        // (supported by OpenAI, Gemini, Mistral). Falls back gracefully for providers
        // that don't support it.
        boolean jsonMode = Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT));

        // Execute: try agent mode first, fall back to legacy
        String responseContent;
        Map<String, Object> responseMetadata = new HashMap<>();
        List<Map<String, Object>> toolTrace = new ArrayList<>();
        boolean usedToolMode = false;

        // Build chat messages without system message for agent mode
        // (agent orchestrator adds system message internally)
        List<ChatMessage> chatMessagesWithoutSystem = messages.stream().filter(m -> !(m instanceof dev.langchain4j.data.message.SystemMessage))
                .toList();

        // === Multi-Model Cascade Branch ===
        var cascadeConfig = task.getModelCascade();
        if (cascadeConfig != null && cascadeConfig.isEnabled() && cascadeConfig.getSteps() != null && !cascadeConfig.getSteps().isEmpty()) {

            // In agent mode, cascade only runs if enableInAgentMode is true
            boolean skipCascade = task.isAgentMode() && !cascadeConfig.isEnableInAgentMode();

            if (!skipCascade) {
                var cascadeResult = CascadingModelExecutor.execute(chatModelRegistry, cascadeConfig, messages, systemMessage, processedParams, task,
                        memory, agentOrchestrator);

                responseContent = cascadeResult.response();

                // Propagate agent result's tool trace if agent mode was used
                if (cascadeResult.agentResult() != null) {
                    toolTrace = cascadeResult.agentResult().trace();
                    usedToolMode = true;
                }

                // Stream final response if streaming is active and no agent result (agent mode
                // already streams)
                if (eventSink != null && responseContent != null && cascadeResult.agentResult() == null && !addToOutputExplicitlyFalse) {
                    eventSink.onToken(responseContent);
                }

                // Store cascade trace for audit
                if (!cascadeResult.trace().isEmpty()) {
                    var cascadeTraceData = dataFactory.createData(KEY_LANGCHAIN + ":cascade:trace:" + task.getId(), cascadeResult.trace());
                    currentStep.storeData(cascadeTraceData);
                }

                // Store cascade metadata in audit
                if (memory.getAuditCollector() != null) {
                    var cascadeModelData = dataFactory.createData("audit:cascade_model",
                            cascadeResult.modelType() + " (step " + cascadeResult.stepUsed() + ")");
                    currentStep.storeData(cascadeModelData);
                    var cascadeConfidenceData = dataFactory.createData("audit:cascade_confidence", String.valueOf(cascadeResult.confidence()));
                    currentStep.storeData(cascadeConfidenceData);
                }

            } else {
                // Agent mode with cascade disabled — use normal agent flow
                var agentResult = agentOrchestrator.executeIfToolsEnabled(chatModel, systemMessage, new ArrayList<>(chatMessagesWithoutSystem), task,
                        memory);
                if (agentResult != null) {
                    responseContent = agentResult.response();
                    toolTrace = agentResult.trace();
                    usedToolMode = true;
                    if (eventSink != null && responseContent != null && !addToOutputExplicitlyFalse) {
                        eventSink.onToken(responseContent);
                    }
                } else {
                    var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonMode);
                    responseContent = chatResult.response();
                    responseMetadata = chatResult.responseMetadata();
                }
            }

        } else {
            // === Standard (non-cascade) execution path ===
            var agentResult = agentOrchestrator.executeIfToolsEnabled(chatModel, systemMessage, new ArrayList<>(chatMessagesWithoutSystem), task,
                    memory);

            if (agentResult != null) {
                // Agent mode — tools execute synchronously, stream final response if sink
                // available
                responseContent = agentResult.response();
                toolTrace = agentResult.trace();
                usedToolMode = true;
                // Stream the final agent response if streaming is active
                if (eventSink != null && responseContent != null && !addToOutputExplicitlyFalse) {
                    eventSink.onToken(responseContent);
                }
            } else if (eventSink != null) {
                // Legacy mode with streaming — try to get a streaming model
                var streamingModel = chatModelRegistry.getOrCreateStreaming(task.getType(), processedParams);
                if (streamingModel != null) {
                    responseContent = streamingLegacyChatExecutor.execute(streamingModel, messages, eventSink);
                } else {
                    // Streaming not supported by this builder — fall back to sync, emit as single
                    // chunk
                    var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonMode);
                    responseContent = chatResult.response();
                    responseMetadata = chatResult.responseMetadata();
                    eventSink.onToken(responseContent);
                }
            } else {
                // Standard non-streaming legacy mode
                var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonMode);
                responseContent = chatResult.response();
                responseMetadata = chatResult.responseMetadata();
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

        // Always store the raw LLM response in memory first — ensures debuggability
        // even if subsequent JSON conversion or postResponse processing fails.
        var langchainData = dataFactory.createData(KEY_LANGCHAIN + ":" + task.getType() + ":" + task.getId(), responseContent);
        currentStep.storeData(langchainData);

        if (Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT))) {
            String trimmed = responseContent != null ? responseContent.trim() : "";
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                var contentAsObject = jsonSerialization.deserialize(responseContent, Map.class);
                templateDataObjects.put(responseObjectName, contentAsObject);
            } else {
                // LLM returned plain text despite structured output instruction
                LOGGER.warn("convertToObject=true but LLM response is not JSON, storing as string");
                templateDataObjects.put(responseObjectName, responseContent);
            }
        } else {
            templateDataObjects.put(responseObjectName, responseContent);
        }

        // Write audit:* memory keys for the audit ledger (only if auditing is enabled)
        if (memory.getAuditCollector() != null) {
            var compiledPrompt = dataFactory.createData("audit:compiled_prompt",
                    systemMessage + "\n---\n" + (processedParams.get(KEY_PROMPT) != null ? processedParams.get(KEY_PROMPT) : ""));
            currentStep.storeData(compiledPrompt);

            if (responseContent != null) {
                var modelResponse = dataFactory.createData("audit:model_response", responseContent);
                currentStep.storeData(modelResponse);
            }

            String modelName = processedParams.getOrDefault("model", task.getType());
            var modelNameData = dataFactory.createData("audit:model_name", modelName);
            currentStep.storeData(modelNameData);
        }

        // Store tool trace if available
        if (!toolTrace.isEmpty()) {
            var traceData = dataFactory.createData(KEY_LANGCHAIN + ":trace:" + task.getType() + ":" + task.getId(), toolTrace);
            currentStep.storeData(traceData);
        }

        // Add to output if configured (or if in agent mode with tools).
        // When addToOutput is explicitly "false" (e.g. structured JSON output with
        // postResponse),
        // do NOT add the raw response — the postResponse will generate proper output.
        boolean shouldAddToOutput = !addToOutputExplicitlyFalse
                && (usedToolMode || Boolean.parseBoolean(processedParams.getOrDefault(KEY_ADD_TO_OUTPUT, "false")));

        if (shouldAddToOutput) {
            var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), responseContent);
            currentStep.storeData(outputData);
            var outputItem = new TextOutputItem(responseContent, 0);
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
        }

        prePostUtils.runPostResponse(memory, task.getPostResponse(), templateDataObjects, 200, false);

        // Strategy 2: Update rolling summary if configured.
        // IMPORTANT: Must run AFTER the current response is added to
        // conversationOutputs,
        // so totalSteps correctly includes this turn when computing
        // summarizeThroughStep.
        // Moving this earlier would cause the summary boundary to be off by 1.
        if (summaryConfig != null && summaryConfig.isEnabled()) {
            try {
                String propertiesContext = null;
                if (summaryConfig.isExcludePropertiesFromSummary()) {
                    var props = memory.getConversationProperties();
                    if (props != null && !props.isEmpty()) {
                        propertiesContext = props.entrySet().stream().filter(e -> !e.getKey().startsWith("conversation:"))
                                .map(e -> e.getKey() + " = " + e.getValue().getValueString()).reduce((a, b) -> a + "\n" + b).orElse(null);
                    }
                }
                conversationSummarizer.updateIfNeeded(memory, summaryConfig, propertiesContext);
            } catch (Exception e) {
                LOGGER.warnf(e, "[SUMMARY] Rolling summary update failed for conversation '%s'. Will retry next turn.", memory.getConversationId());
                // Non-fatal — conversation continues, summary will catch up next turn
            }
        }
    }

    private HashMap<String, String> runTemplateEngineOnParams(Map<String, String> parameters, Map<String, Object> templateDataObjects) {

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

    /**
     * Extracts the current user input text from conversation memory. Used as the
     * query for RAG retrieval.
     */
    private String extractUserInput(IConversationMemory memory) {
        var currentStep = memory.getCurrentStep();
        IData<String> inputData = currentStep.getLatestData("input");
        if (inputData != null && inputData.getResult() != null) {
            return inputData.getResult();
        }
        return null;
    }

    /**
     * Phase 8c-0: Zero-infrastructure RAG via named httpCall. Discovers the named
     * httpCall from the workflow, executes it with the user's query available in
     * template data, and returns the serialized response for context injection.
     *
     * @param memory
     *            conversation memory (for agent/workflow context and template data)
     * @param httpCallName
     *            the name of the ApiCall to execute (from task.httpCallRag)
     * @param userInput
     *            the user's current input (added to template data as "userInput")
     * @param templateDataObjects
     *            mutable template data map
     * @return the serialized JSON response, or null if the httpCall was not found
     */
    private String executeHttpCallRag(IConversationMemory memory, String httpCallName, String userInput, Map<String, Object> templateDataObjects) {

        // Discover all httpCall configs from the workflow
        var stepConfigs = WorkflowTraversal.discoverConfigs(memory, HTTPCALLS_TYPE, ApiCallsConfiguration.class, restAgentStore, restWorkflowStore,
                resourceClientLibrary);

        // Search for the named ApiCall across all httpCall configurations
        for (var stepConfig : stepConfigs) {
            ApiCallsConfiguration httpCallsConfig = stepConfig.config();
            String targetServerUrl = httpCallsConfig.getTargetServerUrl();

            for (ApiCall apiCall : httpCallsConfig.getHttpCalls()) {
                if (httpCallName.equals(apiCall.getName())) {
                    // Found the named httpCall — execute it
                    try {
                        // Make current user input available to httpCall templates
                        templateDataObjects.put("userInput", userInput);

                        Map<String, Object> result = apiCallExecutor.execute(apiCall, memory, templateDataObjects, targetServerUrl);
                        String serialized = jsonSerialization.serialize(result);

                        LOGGER.infof("httpCall RAG '%s' executed: keys=%s, size=%d", httpCallName, result.keySet(), serialized.length());
                        return serialized;

                    } catch (Exception e) {
                        LOGGER.warnf(e, "httpCall RAG execution failed for '%s': %s", httpCallName, e.getMessage());
                        return null;
                    }
                }
            }
        }

        LOGGER.warnf("httpCall RAG: no ApiCall named '%s' found in workflow", httpCallName);
        return null;
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions) throws WorkflowConfigurationException {

        Object uriObj = configuration.get(KEY_URI);
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                return resourceClientLibrary.getResource(uri, LlmConfiguration.class);
            } catch (ServiceException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new WorkflowConfigurationException(e.getLocalizedMessage(), e);
            }
        }

        throw new WorkflowConfigurationException("No resource URI has been defined! [LlmConfiguration]");
    }

    /**
     * Resolve the model name from provider-specific parameter keys. Different
     * providers use different keys: OpenAI uses "modelName", Ollama uses "model",
     * Bedrock/HuggingFace use "modelId", Azure uses "deploymentName".
     */
    private static String resolveModelName(Map<String, String> processedParams) {
        String name = processedParams.get("modelName");
        if (name != null)
            return name;
        name = processedParams.get("model");
        if (name != null)
            return name;
        name = processedParams.get("modelId");
        if (name != null)
            return name;
        return processedParams.get("deploymentName");
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Lang Chain");

        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(KEY_URI, configValue);

        return extensionDescriptor;
    }
}
