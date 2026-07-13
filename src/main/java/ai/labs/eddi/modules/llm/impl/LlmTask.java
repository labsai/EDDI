/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
    public static final TaskId TASK_ID = new TaskId(ID);

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
    private final PromptSnippetService promptSnippetService;
    private final GlobalVariableResolver globalVariableResolver;
    private final CounterweightService counterweightService;
    private final IdentityMaskingService identityMaskingService;
    private final IAttachmentStore attachmentStore;

    // Field-injected so the many direct-construction unit tests are unaffected;
    // null-guarded at the call site.
    @Inject
    AttachmentForwarder attachmentForwarder;

    @Inject
    AttachmentTextExtractor attachmentTextExtractor;

    /**
     * Wire the attachment services into the (constructor-built) AgentOrchestrator
     * after CDI field injection completes, so the {@code readAttachment} tool can
     * be offered. Skipped in direct-construction unit tests (no CDI) — the tool is
     * simply never added there.
     */
    @jakarta.annotation.PostConstruct
    void wireAttachmentServices() {
        if (agentOrchestrator != null) {
            agentOrchestrator.setAttachmentServices(attachmentStore, attachmentTextExtractor);
        }
    }

    // Retained for httpCall RAG discovery + execution (Phase 8c-0)
    private final IApiCallExecutor apiCallExecutor;
    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;

    /**
     * Tool-level HITL kill-switch. When false the tool-approval gate is inert
     * (effective config forced to null) — rolling-upgrade control. Default true.
     */
    @Inject
    @ConfigProperty(name = "eddi.hitl.tool.enabled", defaultValue = "true")
    boolean toolHitlEnabled;

    /**
     * Configured cap (bytes) for serializing the frozen tool-call transcript into a
     * {@link PendingToolCallBatch} on a HITL tool pause. Default MUST equal
     * {@link PendingToolCallBatch#TRANSCRIPT_MAX_BYTES_DEFAULT} (2_000_000) — kept
     * as a literal here because {@code @ConfigProperty}'s {@code defaultValue} must
     * be a compile-time constant string.
     */
    @Inject
    @ConfigProperty(name = "eddi.hitl.tool.transcript-max-bytes", defaultValue = "2000000")
    int toolTranscriptMaxBytes;

    private static final Logger LOGGER = Logger.getLogger(LlmTask.class);

    @Inject
    public LlmTask(IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory, IMemoryItemConverter memoryItemConverter,
            ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, PrePostUtils prePostUtils, ChatModelRegistry chatModelRegistry,
            CalculatorTool calculatorTool, DateTimeTool dateTimeTool, WebSearchTool webSearchTool, DataFormatterTool dataFormatterTool,
            WebScraperTool webScraperTool, TextSummarizerTool textSummarizerTool, PdfReaderTool pdfReaderTool, WeatherTool weatherTool,
            FetchToolResponsePageTool fetchToolResponsePageTool,
            IApiCallExecutor apiCallExecutor, ToolExecutionService toolExecutionService, McpToolProviderManager mcpToolProviderManager,
            A2AToolProviderManager a2aToolProviderManager, IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore,
            RagContextProvider ragContextProvider, IUserMemoryStore userMemoryStore, TokenCounterFactory tokenCounterFactory,
            ConversationSummarizer conversationSummarizer,
            PromptSnippetService promptSnippetService,
            GlobalVariableResolver globalVariableResolver,
            CounterweightService counterweightService,
            IdentityMaskingService identityMaskingService,
            ToolResponseTruncator toolResponseTruncator, TenantQuotaService tenantQuotaService,
            MemorySnapshotService memorySnapshotService, IAttachmentStore attachmentStore,
            AgentSetupService agentSetupService, CapabilityRegistryService capabilityRegistryService,
            IConversationService conversationService, IAgentFactory agentFactory, IAgentStore agentStore,
            IHitlToolJournalStore hitlToolJournalStore) {
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
                textSummarizerTool, pdfReaderTool, weatherTool, fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager, restAgentStore,
                restWorkflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization, memoryItemConverter, userMemoryStore,
                toolResponseTruncator, tenantQuotaService, memorySnapshotService,
                agentSetupService, capabilityRegistryService, conversationService, agentFactory, agentStore,
                hitlToolJournalStore, conversationHistoryBuilder);
        this.ragContextProvider = ragContextProvider;
        this.tokenCounterFactory = tokenCounterFactory;
        this.apiCallExecutor = apiCallExecutor;
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.conversationSummarizer = conversationSummarizer;
        this.promptSnippetService = promptSnippetService;
        this.globalVariableResolver = globalVariableResolver;
        this.counterweightService = counterweightService;
        this.identityMaskingService = identityMaskingService;
        this.attachmentStore = attachmentStore;
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
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

            // Inject prompt snippets into template data — makes all snippets
            // auto-available as {{snippets.<name>}} in system prompts
            Map<String, Object> snippets = promptSnippetService.getAll();
            if (!snippets.isEmpty()) {
                templateDataObjects.put("snippets", snippets);
            }

            // Inject global variables into template data — auto-available as
            // {{vars.<key>}} in system prompts and parameters
            Map<String, Object> globalVars = globalVariableResolver.getTemplateData();
            if (!globalVars.isEmpty()) {
                templateDataObjects.put("vars", globalVars);
            }

            var actions = latestData.getResult();
            if (actions == null) {
                return;
            }

            // === HITL tool-pause resume detection ===
            // A tool pause froze THIS task mid-LLM-loop. On resume, both the pending
            // batch and the human decision are present on memory (set by
            // Conversation.resume). We must NOT re-run the normal path for the paused
            // task — that would re-execute RAG, preRequest property mutations, and the
            // model call from scratch. Instead the paused task re-enters via
            // executeResume, which replays the frozen transcript and applies the
            // verdict; tasks BEFORE it already ran pre-pause, tasks AFTER it run the
            // normal path so the turn completes fully.
            PendingToolCallBatch batch = memory.getHitlPendingToolCalls();
            HitlDecision resumeDecision = memory.getHitlResumeDecision();
            boolean resumeMode = batch != null && resumeDecision != null;
            int resumeIndex = resumeMode ? batch.getLlmTaskIndex() : -1;

            if (resumeMode) {
                // Same-index re-entry FIRST — deterministically, not via the loop.
                // executeResume owns the config-drift guard (bounds + id), so it must
                // run even when resumeIndex is out of range for the current config
                // (redeploy) — otherwise a drifted pause would silently never clear.
                // Tasks BEFORE resumeIndex already ran pre-pause and are skipped.
                executeResume(memory, llmConfig, batch, resumeDecision, currentStep, templateDataObjects);
            }

            var tasks = llmConfig.tasks();
            for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
                // In resume mode, only tasks AFTER the resumed index run the normal
                // path so the turn completes fully; the resumed task was handled above
                // and earlier tasks already ran pre-pause.
                if (resumeMode && taskIndex <= resumeIndex) {
                    continue;
                }
                var task = tasks.get(taskIndex);
                if (task.getActions().contains(MATCH_ALL_OPERATOR) || task.getActions().stream().anyMatch(actions::contains)) {
                    executeTask(memory, task, currentStep, templateDataObjects, taskIndex);
                }
            }

        } catch (ITemplatingEngine.TemplateEngineException | ChatModelRegistry.UnsupportedLlmTaskException | IOException | LifecycleException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * Execute a single task — delegates to LegacyChatExecutor or AgentOrchestrator.
     */
    private void executeTask(IConversationMemory memory, Task task, IWritableConversationStep currentStep, Map<String, Object> templateDataObjects,
                             int llmTaskIndex)
            throws ITemplatingEngine.TemplateEngineException, ChatModelRegistry.UnsupportedLlmTaskException, IOException, LifecycleException {

        // === Tool-approval (tool-level HITL) effective config resolution ===
        // Per-task override fully replaces the agent-level default (no merging). The
        // agent default reaches memory via a transient carrier set at turn start.
        // The feature flag lets operators disable the gate cluster-wide during a
        // rolling upgrade — when false the effective config is treated as null
        // (gate inert), byte-identical to the pre-HITL path.
        ToolApprovalsConfig effectiveToolApprovals = null;
        if (toolHitlEnabled) {
            effectiveToolApprovals = task.getToolApprovals() != null
                    ? task.getToolApprovals()
                    : memory.getAgentToolApprovalsConfig();
        }

        var processedParams = runTemplateEngineOnParams(task.getParameters(), templateDataObjects);

        // Parse history parameters
        String systemMessage = processedParams.getOrDefault(KEY_SYSTEM_MESSAGE, "");

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

        // === Behavioral Counterweight & Identity Masking (Wave 1) ===
        // Identity masking is prepended first (if enabled), then counterweight
        // is applied. Order matters: masking defines identity policy,
        // counterweight defines behavioral safety level.
        systemMessage = identityMaskingService.apply(systemMessage, task.getIdentityMasking());

        // Resolve channel tag for strict→cautious downgrade on scheduled agents
        String channelTag = null;
        IData<String> channelData = currentStep.getLatestData("channel:tag");
        if (channelData != null && channelData.getResult() != null) {
            channelTag = channelData.getResult();
        }
        systemMessage = counterweightService.apply(systemMessage, task.getCounterweight(), channelTag);

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

        // Resolve global variable references in task type BEFORE any model
        // lookups — used by token estimator, sync model, and streaming model.
        var resolvedType = globalVariableResolver.resolveValue(task.getType());

        List<ChatMessage> messages;
        if (maxContextTokens != null && maxContextTokens > 0) {
            // Resolve model name from provider-specific parameter keys
            String resolvedModelName = resolveModelName(processedParams);
            var estimator = tokenCounterFactory.getEstimator(resolvedType, resolvedModelName);
            messages = conversationHistoryBuilder.buildTokenAwareMessages(memory, systemMessage, processedParams.get(KEY_PROMPT), maxContextTokens,
                    anchorFirstSteps, includeFirstAgentMessage, estimator, summaryPrefix, skipSteps);
        } else {
            // Legacy step-count windowing
            messages = conversationHistoryBuilder.buildMessages(memory, systemMessage, processedParams.get(KEY_PROMPT), logSizeLimit,
                    includeFirstAgentMessage, summaryPrefix, skipSteps);
        }

        // Forward the current step's attachments to the LLM as multimodal content,
        // gated on the resolved (provider, model) capabilities, honoring any
        // per-task multimodal overrides.
        if (attachmentForwarder != null) {
            var mm = task.getMultimodal();
            var vision = mm != null
                    ? ModelCapabilityService.Support.parse(mm.getVision())
                    : ModelCapabilityService.Support.AUTO;
            var documents = mm != null
                    ? ModelCapabilityService.Support.parse(mm.getDocuments())
                    : ModelCapabilityService.Support.AUTO;
            var audio = mm != null
                    ? ModelCapabilityService.Support.parse(mm.getAudio())
                    : ModelCapabilityService.Support.AUTO;
            attachmentForwarder.forward(messages, memory, resolvedType, resolveModelName(processedParams),
                    vision, documents, audio);
        }

        if (messages.isEmpty()) {
            return;
        }

        var chatModel = chatModelRegistry.getOrCreate(resolvedType, processedParams);
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
                        memory, agentOrchestrator, effectiveToolApprovals, llmTaskIndex, toolTranscriptMaxBytes);

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
                        memory, effectiveToolApprovals, llmTaskIndex, toolTranscriptMaxBytes);
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
                    memory, effectiveToolApprovals, llmTaskIndex, toolTranscriptMaxBytes);

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
                var streamingModel = chatModelRegistry.getOrCreateStreaming(resolvedType, processedParams);
                if (streamingModel != null) {
                    responseContent = streamingLegacyChatExecutor.execute(streamingModel, messages, eventSink);
                } else {
                    // Streaming not supported by this builder — fall back to sync, emit as single
                    // chunk
                    var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonMode);
                    responseContent = chatResult.response();
                    responseMetadata = chatResult.responseMetadata();
                    if (!addToOutputExplicitlyFalse) {
                        eventSink.onToken(responseContent);
                    }
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
                LOGGER.warnf(e, "[SUMMARY] Rolling summary update failed for conversation '%s'. Will retry next turn.",
                        sanitize(memory.getConversationId()));
                // Non-fatal — conversation continues, summary will catch up next turn
            }
        }
    }

    /**
     * Re-enter the paused LLM task after a HITL tool pause was resolved by a human.
     * <p>
     * This is the RESUME mirror of {@link #executeTask}: it rebuilds the chat model
     * for the paused task, hands the frozen batch + human decision to
     * {@link AgentOrchestrator#resumeToolLoop} (which replays the transcript and
     * applies the verdicts — implemented in Task 9), then stores the final result
     * EXACTLY like the normal path and runs {@code postResponse}.
     * <p>
     * <strong>Pre-LLM bypass:</strong> unlike {@code executeTask}, this method
     * deliberately skips every side-effecting step that already ran before the
     * pause — httpCall RAG, vector RAG, {@code preRequest} property instructions,
     * history rebuild + multimodal enhancement, the cascade branch, and
     * identity-masking / counterweight re-application. Those are baked into the
     * frozen transcript; re-running them would double-execute external calls and
     * mutate state twice.
     */
    private void executeResume(IConversationMemory memory, LlmConfiguration llmConfig, PendingToolCallBatch batch, HitlDecision resumeDecision,
                               IWritableConversationStep currentStep, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, ChatModelRegistry.UnsupportedLlmTaskException, IOException, LifecycleException {

        // === Task-identity binding (config-drift guard) ===
        // The batch records the task index AND id at pause time. If the workflow/llm
        // config was redeployed while awaiting approval, the task at that index may be
        // gone or different. In that case we FAIL SAFE: the gated tools never ran, so
        // we degrade gracefully — surface a clear message, clear the pause state, and
        // let the rest of the pipeline continue. We never guess and execute.
        int index = batch.getLlmTaskIndex();
        var tasks = llmConfig.tasks();
        Task task = (index >= 0 && index < tasks.size()) ? tasks.get(index) : null;
        if (task == null || !Objects.equals(task.getId(), batch.getLlmTaskId())) {
            clearToolPauseState(memory);
            String driftMessage = "The pending approval could not be applied because the agent's configuration changed. "
                    + "No gated action was executed.";
            var driftData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":drift", driftMessage);
            currentStep.storeData(driftData);
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(new TextOutputItem(driftMessage, 0)));
            // Audit: no lightweight hitl.tool.* audit collector is reachable from this
            // task (the AuditEntry record is built by LifecycleManager per-task with
            // HMAC context we do not have here). WARN-log with a distinctive marker so
            // operators can alert on drift; a first-class audit hook can be added later.
            LOGGER.warnf("hitl.tool.config_drift: pending tool approval discarded for conversation '%s' — recorded task id '%s' at index %d "
                    + "no longer matches deployed config (found '%s'). No gated action executed.",
                    sanitize(memory.getConversationId()), batch.getLlmTaskId(), index, task != null ? task.getId() : "<out-of-bounds>");
            return;
        }

        // === Rebuild the chat model for THIS task only (normal-path parity) ===
        var processedParams = runTemplateEngineOnParams(task.getParameters(), templateDataObjects);
        var resolvedType = globalVariableResolver.resolveValue(task.getType());
        var chatModel = chatModelRegistry.getOrCreate(resolvedType, processedParams);

        // === Hand off to the resume loop (Task 9) ===
        // Pass the cluster-wide kill-switch so the continuation's gate resolution
        // matches the live path (executeTask) — a disabled gate stays inert on resume.
        var result = agentOrchestrator.resumeToolLoop(chatModel, task, memory, batch, resumeDecision, templateDataObjects,
                toolHitlEnabled);

        String responseContent = result != null ? result.response() : null;
        List<Map<String, Object>> toolTrace = result != null && result.trace() != null ? result.trace() : new ArrayList<>();

        // === Store the result EXACTLY like the normal path (executeTask) ===
        var responseObjectName = task.getResponseObjectName();
        if (isNullOrEmpty(responseObjectName)) {
            responseObjectName = task.getId();
        }

        var langchainData = dataFactory.createData(KEY_LANGCHAIN + ":" + task.getType() + ":" + task.getId(), responseContent);
        currentStep.storeData(langchainData);

        if (Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT))) {
            String trimmed = responseContent != null ? responseContent.trim() : "";
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                var contentAsObject = jsonSerialization.deserialize(responseContent, Map.class);
                templateDataObjects.put(responseObjectName, contentAsObject);
            } else {
                LOGGER.warn("convertToObject=true but resumed LLM response is not JSON, storing as string");
                templateDataObjects.put(responseObjectName, responseContent);
            }
        } else {
            templateDataObjects.put(responseObjectName, responseContent);
        }

        // Audit keys (mirror executeTask)
        if (memory.getAuditCollector() != null) {
            if (responseContent != null) {
                var modelResponse = dataFactory.createData("audit:model_response", responseContent);
                currentStep.storeData(modelResponse);
            }
            String modelName = processedParams.getOrDefault("model", task.getType());
            var modelNameData = dataFactory.createData("audit:model_name", modelName);
            currentStep.storeData(modelNameData);
        }

        // Tool trace (mirror executeTask)
        if (!toolTrace.isEmpty()) {
            var traceData = dataFactory.createData(KEY_LANGCHAIN + ":trace:" + task.getType() + ":" + task.getId(), toolTrace);
            currentStep.storeData(traceData);
        }

        // Output add. On resume the task used tool mode (that is why it paused), so
        // add the final response unless addToOutput was explicitly false.
        boolean addToOutputExplicitlyFalse = "false".equalsIgnoreCase(processedParams.get(KEY_ADD_TO_OUTPUT));
        if (!addToOutputExplicitlyFalse) {
            var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), responseContent);
            currentStep.storeData(outputData);
            var outputItem = new TextOutputItem(responseContent, 0);
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
        }

        // postResponse DOES run on resume — it reacts to the final response, which
        // only exists now that the tool loop completed.
        prePostUtils.runPostResponse(memory, task.getPostResponse(), templateDataObjects, 200, false);

        // Batch consumed — clear the transient tool-pause state so the next turn does
        // not re-detect resume mode.
        clearToolPauseState(memory);
    }

    /**
     * Nulls the transient tool-pause state on memory (pending batch, resume
     * decision, pause type) after the batch has been consumed on resume — or when a
     * config-drift degradation discards it. Mirrors the private clearing helper on
     * {@code Conversation}; kept local because {@code IConversationMemory} exposes
     * the three setters but no combined clear method.
     */
    private void clearToolPauseState(IConversationMemory memory) {
        memory.setHitlPendingToolCalls(null);
        memory.setHitlResumeDecision(null);
        memory.setHitlPauseType(null);
    }

    /**
     * Parameters that should NOT be processed by the template engine (credentials,
     * secrets).
     */
    private static final Set<String> TEMPLATE_SKIP_PARAMS = Set.of("apiKey", "signingSecret", "appPassword", "botToken");

    private HashMap<String, String> runTemplateEngineOnParams(Map<String, String> parameters, Map<String, Object> templateDataObjects) {

        var processedParams = new HashMap<>(parameters);
        processedParams.forEach((key, value) -> {
            try {
                if (!isNullOrEmpty(value) && !TEMPLATE_SKIP_PARAMS.contains(key)) {
                    processedParams.put(key, templatingEngine.processTemplate(value, templateDataObjects));
                }
            } catch (ITemplatingEngine.TemplateEngineException e) {
                LOGGER.errorf(e, "Template processing failed for LLM parameter '%s': %s", key, e.getLocalizedMessage());
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
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(new TaskId(ID));
        extensionDescriptor.setDisplayName("Lang Chain");

        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(KEY_URI, configValue);

        return extensionDescriptor;
    }
}
