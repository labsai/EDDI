/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.hitl.tools.TaskToolApprovalsResolver;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import dev.langchain4j.data.message.SystemMessage;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;
import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.engine.memory.MemoryKeys.LANGCHAIN_TRACE_PREFIX;
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
    private final CascadingModelExecutor cascadingModelExecutor;
    private final RagContextProvider ragContextProvider;
    private final TokenCounterFactory tokenCounterFactory;
    private final ConversationSummarizer conversationSummarizer;
    private final PromptSnippetService promptSnippetService;
    private final GlobalVariableResolver globalVariableResolver;
    private final CounterweightService counterweightService;
    private final IdentityMaskingService identityMaskingService;
    private final MeterRegistry meterRegistry;

    /**
     * The tool-calling agent loop — an injected collaborator, so a test can pass a
     * mock straight to the constructor. This used to be built here via {@code new}
     * from 22 constructor parameters that served no other purpose, leaving the
     * agent-mode branches reachable only through
     * {@code getDeclaredField("agentOrchestrator")} reflection.
     */
    private final IAgentOrchestrator agentOrchestrator;

    // Field-injected so the many direct-construction unit tests are unaffected;
    // null-guarded at the call site.
    @Inject
    AttachmentForwarder attachmentForwarder;

    // Retained for httpCall RAG discovery + execution (Phase 8c-0)
    private final IApiCallExecutor apiCallExecutor;
    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;

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

    /**
     * When true (the default) and the conversation has an event sink, tool-enabled
     * turns run their model rounds over the provider's streaming transport via
     * {@link ToolLoopStreamingChatModel}, so the final answer (and any interim
     * commentary) reaches the client token-by-token instead of as one chunk after
     * the whole loop finishes. Kill-switch, not a feature flag: turning it off
     * restores the previous single-chunk behaviour exactly.
     */
    @Inject
    @ConfigProperty(name = "eddi.llm.tool-loop.streaming.enabled", defaultValue = "true")
    boolean toolLoopStreamingEnabled;

    private static final Logger LOGGER = Logger.getLogger(LlmTask.class);

    @Inject
    public LlmTask(IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory, IMemoryItemConverter memoryItemConverter,
            ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, PrePostUtils prePostUtils, ChatModelRegistry chatModelRegistry,
            IApiCallExecutor apiCallExecutor, IAgentStore agentStore, IWorkflowStore workflowStore,
            RagContextProvider ragContextProvider, TokenCounterFactory tokenCounterFactory,
            ConversationSummarizer conversationSummarizer,
            PromptSnippetService promptSnippetService,
            GlobalVariableResolver globalVariableResolver,
            CounterweightService counterweightService,
            IdentityMaskingService identityMaskingService,
            IAgentOrchestrator agentOrchestrator, ConversationHistoryBuilder conversationHistoryBuilder,
            MeterRegistry meterRegistry, CallerIdentityContext callerIdentityContext) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.prePostUtils = prePostUtils;

        this.meterRegistry = meterRegistry;
        this.chatModelRegistry = chatModelRegistry;
        this.conversationHistoryBuilder = conversationHistoryBuilder;
        this.legacyChatExecutor = new LegacyChatExecutor();
        this.streamingLegacyChatExecutor = new StreamingLegacyChatExecutor(meterRegistry);
        this.agentOrchestrator = agentOrchestrator;
        this.ragContextProvider = ragContextProvider;
        this.tokenCounterFactory = tokenCounterFactory;
        this.apiCallExecutor = apiCallExecutor;
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.conversationSummarizer = conversationSummarizer;
        this.promptSnippetService = promptSnippetService;
        this.globalVariableResolver = globalVariableResolver;
        this.counterweightService = counterweightService;
        this.identityMaskingService = identityMaskingService;
        this.cascadingModelExecutor = new CascadingModelExecutor(chatModelRegistry, globalVariableResolver, templatingEngine, legacyChatExecutor,
                streamingLegacyChatExecutor, meterRegistry, callerIdentityContext);
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
        // How a task-level toolApprovals combines with the agent-level default is
        // decided by eddi.hitl.tool.task-approvals.mode — strict (the default: the
        // task can only STRENGTHEN the agent gate) or replace (the historical
        // wholesale override). See TaskToolApprovalsResolver for the exact merge.
        // The agent default reaches memory via a transient carrier set at turn
        // start. The feature flag lets operators disable the gate cluster-wide
        // during a rolling upgrade — when false the effective config is treated as
        // null (gate inert), byte-identical to the pre-HITL path.
        ToolApprovalsConfig effectiveToolApprovals = null;
        if (toolHitlEnabled) {
            effectiveToolApprovals = TaskToolApprovalsResolver.resolve(
                    memory.getAgentToolApprovalsConfig(), task.getToolApprovals());
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

        // Finding F7: maxContextTokens explicitly excludes the system prompt, so
        // nothing bounded the RAG blocks appended to it. Both appends below are capped
        // by the task's maxRagContextChars.
        int maxRagContextChars = RagContextProvider.resolveMaxChars(task);

        // Phase 8c-0: Zero-infrastructure RAG via named httpCall
        String httpCallRag = task.getHttpCallRag();
        if (!isNullOrEmpty(httpCallRag) && userInput != null) {
            try {
                String httpCallContext = capRagContext(executeHttpCallRag(memory, httpCallRag, userInput, templateDataObjects), maxRagContextChars,
                        "httpCall RAG '" + httpCallRag + "'");
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
                String ragContext = capRagContext(ragContextProvider.retrieveContext(memory, task, userInput), maxRagContextChars,
                        "vector RAG for task '" + taskId + "'");
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

        // Finding F7: last line of defence — the assembled prompt has now absorbed RAG
        // context, counterweight, identity masking and format blocks, none of which
        // maxContextTokens covers. Opt-in (-1 by default).
        systemMessage = capSystemPrompt(systemMessage, task.getMaxSystemPromptChars(), taskId);

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

        // Determine whether the multi-model cascade will run. The base model is only
        // needed outside the active-cascade branch, so create it lazily to avoid an
        // unused model build when the cascade owns the request.
        var cascadeConfig = task.getModelCascade();
        boolean cascadeConfigured = cascadeConfig != null && cascadeConfig.isEnabled() && cascadeConfig.getSteps() != null
                && !cascadeConfig.getSteps().isEmpty();
        boolean skipCascade = cascadeConfigured && task.isAgentMode() && !cascadeConfig.isEnableInAgentMode();
        boolean cascadeActive = cascadeConfigured && !skipCascade;

        var chatModel = cascadeActive ? null : chatModelRegistry.getOrCreate(resolvedType, processedParams);
        prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.getPreRequest());

        // Detect streaming mode — event sink is set when SSE endpoint is used
        ConversationEventSink eventSink = memory.getEventSink();

        // When addToOutput is explicitly "false" (structured JSON with postResponse),
        // do NOT stream or add the raw response — the postResponse will generate proper
        // output.
        boolean addToOutputExplicitlyFalse = "false".equalsIgnoreCase(processedParams.get(KEY_ADD_TO_OUTPUT));

        // When convertToObject is true, request a native JSON response format at the
        // API level. The format is set per REQUEST (never baked into the cached model)
        // and only for providers whose binding accepts it — including the tools-aware
        // distinction that keeps Gemini from being sent JSON mode and function calling
        // together. Every execution mode below gets the same policy, so a tool-enabled
        // or streaming agent is no longer silently downgraded to prompt-only JSON.
        boolean jsonMode = Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT));
        var jsonPolicy = JsonResponseFormatPolicy.of(jsonMode, resolvedType, task.getJsonResponseFormat());

        // Execute: try agent mode first, fall back to legacy
        String responseContent;
        Map<String, Object> responseMetadata = new HashMap<>();
        List<Map<String, Object>> toolTrace = new ArrayList<>();
        boolean usedToolMode = false;
        // Real model name of the cascade-selected step, for the audit ledger (#5).
        String cascadeAuditModel = null;

        // Build chat messages without system message for agent mode
        // (agent orchestrator adds system message internally)
        List<ChatMessage> chatMessagesWithoutSystem = messages.stream().filter(m -> !(m instanceof SystemMessage))
                .toList();

        // === Multi-Model Cascade Branch ===
        if (cascadeActive) {
            boolean convertToObject = Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT));
            boolean allowLiveStreaming = eventSink != null && !addToOutputExplicitlyFalse;
            var cascadeResult = cascadingModelExecutor.execute(cascadeConfig, messages, systemMessage, processedParams, task, memory,
                    agentOrchestrator, templateDataObjects, jsonMode, convertToObject, allowLiveStreaming,
                    effectiveToolApprovals, llmTaskIndex, toolTranscriptMaxBytes);

            responseContent = cascadeResult.response();
            cascadeAuditModel = cascadeResult.modelType() + "/" + cascadeResult.modelName();

            // Propagate agent result's tool trace if agent mode was used
            if (cascadeResult.agentResult() != null) {
                toolTrace = cascadeResult.agentResult().trace();
                usedToolMode = true;
            }

            // Surface real token usage + cost as response metadata (#gap: cost/token
            // evidence). Previously the cascade discarded these, so
            // responseMetadataObjectName yielded {}.
            if (cascadeResult.tokenUsage() != null && !cascadeResult.tokenUsage().isEmpty()) {
                responseMetadata.put("tokenUsage", cascadeResult.tokenUsage());
            }

            // Carry the winning step's validation signals through. Without these,
            // responseValidation's onTruncation / onContentFilter / onStreamingTimeout
            // policies are unreachable whenever the cascade is enabled — a truncated or
            // filtered answer would reach the user even with action "error" configured.
            var cascadeMetadata = cascadeResult.responseMetadata();
            if (cascadeMetadata != null) {
                if (cascadeMetadata.get("warning") != null) {
                    responseMetadata.put("warning", cascadeMetadata.get("warning"));
                }
                if (cascadeMetadata.get("finishReason") != null) {
                    responseMetadata.put("finishReason", cascadeMetadata.get("finishReason"));
                }
                if (Boolean.TRUE.equals(cascadeMetadata.get("streamingTimeout"))) {
                    responseMetadata.put("streamingTimeout", true);
                }
            }
            responseMetadata.put("cascadeCostUsd", cascadeResult.runCostUsd());
            // Tool spend of the run. This branch builds a FRESH metadata map instead of
            // adopting the agent's, so without this the entire tool cost of an agent-mode
            // cascade (the DEFAULT — enableInAgentMode defaults to true) never reaches
            // accumulateAuditEvidence's cascadeCostUsd + toolCostUsd sum, and the ledger
            // reports token cost only. The non-cascade branches below assign the agent's
            // whole map and always carried it.
            responseMetadata.put("toolCostUsd", cascadeResult.runToolCostUsd());
            responseMetadata.put("cascadeModel", cascadeAuditModel);
            responseMetadata.put("cascadeStep", cascadeResult.stepUsed());
            responseMetadata.put("cascadeConfidence", cascadeResult.confidence());

            // Emit the final response to the stream unless the executor already streamed
            // it live token-by-token (legacy final-step streaming). Agent-mode results
            // are emitted here as a single chunk — the cascade is now the deliberate
            // EXCEPTION to tool-loop streaming: CascadingModelExecutor owns per-step
            // model construction, timeouts and escalation, so the streaming bridge the
            // two non-cascade branches hand to the loop stops at this boundary rather
            // than threading a second transport through the cascade's own machinery.
            if (eventSink != null && responseContent != null && !addToOutputExplicitlyFalse && !cascadeResult.streamedLive()) {
                // F10: this is the same single-chunk downgrade the two non-cascade agent
                // paths record. Agent mode is the DEFAULT here (enableInAgentMode defaults
                // to true), so leaving it uninstrumented meant the most common streaming
                // downgrade in the product was the one nobody could observe.
                if (cascadeResult.agentResult() != null) {
                    recordStreamingDowngrade(responseMetadata, task, responseContent);
                }
                eventSink.onToken(responseContent);
            }

            // Store cascade trace for audit
            if (!cascadeResult.trace().isEmpty()) {
                var cascadeTraceData = dataFactory.createData(KEY_LANGCHAIN + ":cascade:trace:" + task.getId(), cascadeResult.trace());
                currentStep.storeData(cascadeTraceData);
            }

            // Store cascade metadata in audit — real model name + provider + step + cost
            // (#5). Confidence goes under AUDIT_CONFIDENCE as a Double: the former
            // "audit:cascade_confidence" String had no reader anywhere, while the
            // IData<Double> slot LifecycleManager reads had no writer. Cost and token
            // usage are accumulated below from responseMetadata, together with every
            // other execution path, so they are not written twice here.
            if (memory.getAuditCollector() != null) {
                String cascadeModelDesc = cascadeAuditModel + " (step " + cascadeResult.stepUsed() + ")";
                currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_CASCADE_MODEL, cascadeModelDesc));
                currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_CONFIDENCE, cascadeResult.confidence()));
            }

        } else if (skipCascade) {
            // Agent mode with cascade disabled — use normal agent flow. The streaming
            // bridge is handed ONLY to the tool loop — see runToolLoopIfEnabled.
            var outcome = runToolLoopIfEnabled(chatModel, systemMessage, chatMessagesWithoutSystem, task, memory,
                    effectiveToolApprovals, llmTaskIndex, jsonPolicy, eventSink, addToOutputExplicitlyFalse,
                    resolvedType, processedParams);
            if (outcome != null) {
                responseContent = outcome.response();
                toolTrace = outcome.trace();
                responseMetadata = outcome.responseMetadata();
                usedToolMode = true;
            } else {
                var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonPolicy);
                responseContent = chatResult.response();
                responseMetadata = chatResult.responseMetadata();
                // Forward the buffered response to the stream so an SSE client is not left
                // empty.
                if (eventSink != null && responseContent != null && !addToOutputExplicitlyFalse) {
                    eventSink.onToken(responseContent);
                }
            }

        } else {
            // === Standard (non-cascade) execution path ===
            var outcome = runToolLoopIfEnabled(chatModel, systemMessage, chatMessagesWithoutSystem, task, memory,
                    effectiveToolApprovals, llmTaskIndex, jsonPolicy, eventSink, addToOutputExplicitlyFalse,
                    resolvedType, processedParams);

            if (outcome != null) {
                responseContent = outcome.response();
                toolTrace = outcome.trace();
                responseMetadata = outcome.responseMetadata();
                usedToolMode = true;
            } else if (eventSink != null) {
                // Legacy mode with streaming — try to get a streaming model
                var streamingModel = chatModelRegistry.getOrCreateStreaming(resolvedType, processedParams);
                if (streamingModel != null) {
                    var streamingResult = streamingLegacyChatExecutor.execute(streamingModel, messages, eventSink, task, jsonPolicy);
                    responseContent = streamingResult.response();
                    responseMetadata.putAll(streamingResult.metadata());
                } else {
                    // Streaming not supported by this builder — fall back to sync, emit as single
                    // chunk
                    var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonPolicy);
                    responseContent = chatResult.response();
                    responseMetadata = chatResult.responseMetadata();
                    if (!addToOutputExplicitlyFalse) {
                        eventSink.onToken(responseContent);
                    }
                }
            } else {
                // Standard non-streaming legacy mode
                var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonPolicy);
                responseContent = chatResult.response();
                responseMetadata = chatResult.responseMetadata();
            }
        }

        // === Response Validation (Phase D) ===
        responseContent = applyResponseValidation(responseContent, responseMetadata, task, currentStep);

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
            var compiledPrompt = dataFactory.createData(MemoryKeys.AUDIT_COMPILED_PROMPT,
                    systemMessage + "\n---\n" + (processedParams.get(KEY_PROMPT) != null ? processedParams.get(KEY_PROMPT) : ""));
            currentStep.storeData(compiledPrompt);

            if (responseContent != null) {
                var modelResponse = dataFactory.createData(MemoryKeys.AUDIT_MODEL_RESPONSE, responseContent);
                currentStep.storeData(modelResponse);
            }

            // Under cascade, record the actual winning model (provider/model), not the
            // task-level default — an auditor must be able to reconstruct which model
            // produced the answer (#5).
            String modelName = cascadeAuditModel != null ? cascadeAuditModel : processedParams.getOrDefault("model", task.getType());
            var modelNameData = dataFactory.createData(MemoryKeys.AUDIT_MODEL_NAME, modelName);
            currentStep.storeData(modelNameData);

            accumulateAuditEvidence(currentStep, responseMetadata, toolTrace, task);
        }

        // Store tool trace if available
        if (!toolTrace.isEmpty()) {
            var traceData = dataFactory.createData(LANGCHAIN_TRACE_PREFIX + task.getType() + ":" + task.getId(), toolTrace);
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
            // Only add to conversation output if there is actual text.
            // Null/blank responses (e.g. from token budget exhaustion or
            // thinking-only turns) should not produce empty message bubbles.
            if (producesRenderableOutput(responseContent)) {
                var outputItem = new TextOutputItem(responseContent, 0);
                currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
            } else {
                // DEBUG, not WARN: this is a documented-expected outcome (a
                // thinking-only turn, or an exhausted token budget), so at WARN it
                // recurs in healthy operation without telling an operator anything
                // they can act on. Carries the conversation id so that when someone
                // does turn debug on to chase "the agent didn't answer", the line
                // identifies which conversation — which the WARN never did.
                LOGGER.debugf("LLM response was null or blank for task '%s' (type=%s) in conversation '%s' — skipping output",
                        task.getId(), task.getType(), sanitize(memory.getConversationId()));
            }
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
                // Finding F13: the summarizer used to be pinned to a hardcoded
                // vendor/model default (anthropic/claude-sonnet-4-6) that had no way to
                // reach the parent task's credentials. Resolve an effective config that
                // inherits the parent's provider and model when the summary config leaves
                // them unset, so enabling conversationSummary works with only the parent
                // task configured.
                // Resolving the provider and model is only half of F13 — without the
                // parent task's apiKey/baseUrl the summarizer cannot authenticate at all,
                // and the failure is swallowed below as a WARN, which is exactly how the
                // rolling summary came to silently never materialise. Pass the resolved
                // parameters through so inheritance actually reaches ChatModelRegistry.
                // Those parameters are the PARENT provider's, so what may travel is
                // decided by resolveInheritedSummaryParameters — a summary config naming a
                // different vendor gets the neutral tuning values and none of the
                // credentials.
                var effectiveSummaryConfig = resolveEffectiveSummaryConfig(summaryConfig, resolvedType, resolveModelName(processedParams));
                conversationSummarizer.updateIfNeeded(memory, effectiveSummaryConfig, propertiesContext,
                        resolveInheritedSummaryParameters(processedParams, resolvedType, effectiveSummaryConfig.getLlmProvider()));
            } catch (Exception e) {
                LOGGER.warnf(e, "[SUMMARY] Rolling summary update failed for conversation '%s'. Will retry next turn.",
                        sanitize(memory.getConversationId()));
                // Non-fatal — conversation continues, summary will catch up next turn
            }
        }
    }

    /**
     * Folds one LLM call's usage evidence into the step-level audit totals.
     * <p>
     * A single turn can drive several LLM calls — one per matching config sub-task,
     * plus every escalated cascade step and tool-loop iteration inside each. The
     * ledger has to report the turn's total, but {@code getLatestData} is
     * last-write-wins, so each contributor read-modify-writes rather than
     * overwriting. Cost sums the LLM token spend and the tracked tool cost. A
     * cascade run prices its own steps and reports the total as
     * {@code cascadeCostUsd}; a plain call is priced here from the task-level
     * {@code inputPricePer1M}/{@code outputPricePer1M} — the key presence
     * discriminates, so cascade tokens are never priced twice. Unpriced configs
     * contribute $0 exactly as before.
     */
    private void accumulateAuditEvidence(IWritableConversationStep currentStep, Map<String, Object> responseMetadata,
                                         List<Map<String, Object>> toolTrace, Task task) {
        if (responseMetadata != null) {
            Map<?, ?> tokenUsage = responseMetadata.get("tokenUsage") instanceof Map<?, ?> tu ? tu : null;
            if (tokenUsage != null) {
                accumulateTokenUsage(currentStep, tokenUsage);
            }
            double llmCostUsd = responseMetadata.containsKey("cascadeCostUsd")
                    ? asDouble(responseMetadata.get("cascadeCostUsd"))
                    : TokenPricing.cost(task.getInputPricePer1M(), task.getOutputPricePer1M(), tokenUsage);
            accumulateCost(currentStep, llmCostUsd + asDouble(responseMetadata.get("toolCostUsd")));
        }
        accumulateToolCalls(currentStep, toolTrace, task.getId());
    }

    private void accumulateTokenUsage(IWritableConversationStep currentStep, Map<?, ?> delta) {
        if (delta.isEmpty()) {
            return;
        }
        Map<String, Object> total = new LinkedHashMap<>();
        IData<Map<String, Object>> existing = currentStep.getLatestData(MemoryKeys.AUDIT_TOKEN_USAGE);
        if (existing != null && existing.getResult() != null) {
            total.putAll(existing.getResult());
        }
        for (String field : AgentOrchestrator.TOKEN_USAGE_FIELDS) {
            // Only touch a count the provider actually reported (or that a previous call
            // already contributed) — otherwise a provider that omits totalTokens would
            // materialize a bogus 0 next to real input/output numbers.
            if (delta.containsKey(field) || total.containsKey(field)) {
                total.put(field, asLong(total.get(field)) + asLong(delta.get(field)));
            }
        }
        currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_TOKEN_USAGE, total));
    }

    private void accumulateToolCalls(IWritableConversationStep currentStep, List<Map<String, Object>> toolTrace, String llmTaskId) {
        if (toolTrace == null || toolTrace.isEmpty()) {
            return;
        }
        List<Object> calls = new ArrayList<>();
        IData<Map<String, Object>> existing = currentStep.getLatestData(MemoryKeys.AUDIT_TOOL_CALLS);
        if (existing != null && existing.getResult() != null && existing.getResult().get("calls") instanceof List<?> prior) {
            calls.addAll(prior);
        }
        for (Map<String, Object> entry : toolTrace) {
            if (entry == null) {
                continue;
            }
            var augmented = new LinkedHashMap<String, Object>(entry);
            if (llmTaskId != null) {
                // Which config sub-task issued the call — otherwise a merged list from
                // several sub-tasks is unattributable.
                augmented.put("llmTaskId", llmTaskId);
            }
            calls.add(augmented);
        }
        Map<String, Object> toolCalls = new LinkedHashMap<>();
        toolCalls.put("calls", calls);
        currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_TOOL_CALLS, toolCalls));
    }

    private void accumulateCost(IWritableConversationStep currentStep, double delta) {
        // !(delta > 0.0), not delta <= 0.0: NaN fails every comparison, so the
        // latter admits it — and one NaN poisons the running total forever,
        // silently disabling every dollar ceiling that compares against it.
        if (!Double.isFinite(delta) || !(delta > 0.0)) {
            return;
        }
        double total = delta;
        IData<Double> existing = currentStep.getLatestData(MemoryKeys.AUDIT_COST);
        if (existing != null && existing.getResult() != null) {
            total += existing.getResult();
        }
        currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_COST, total));
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * Applies config-driven response validation policies. Inspects the LLM response
     * and metadata for anomalies (empty, truncated, filtered, refused, streaming
     * timeout) and applies the configured action.
     *
     * @return the (possibly modified) response content
     */
    private String applyResponseValidation(String responseContent, Map<String, Object> responseMetadata,
                                           Task task, IWritableConversationStep currentStep)
            throws LifecycleException {

        ResponseValidation validation = task.getResponseValidation();
        if (validation == null || !validation.isEnabled()) {
            return responseContent;
        }

        String warning = responseMetadata != null ? (String) responseMetadata.get("warning") : null;
        boolean streamingTimeout = responseMetadata != null && Boolean.TRUE.equals(responseMetadata.get("streamingTimeout"));

        // 1. Empty response check
        if (isNullOrEmpty(responseContent)) {
            responseContent = applyValidationAction(validation.getOnEmpty(), "empty_response",
                    "LLM returned empty response", responseContent, task, currentStep);
        }

        // 2. Truncation check (finishReason=LENGTH)
        if ("truncated".equals(warning)) {
            responseContent = applyValidationAction(validation.getOnTruncation(), "truncated_response",
                    "LLM response was truncated (finishReason=LENGTH)", responseContent, task, currentStep);
        }

        // 3. Content filter check
        if ("content_filter".equals(warning)) {
            responseContent = applyValidationAction(validation.getOnContentFilter(), "content_filter",
                    "LLM response was blocked by content filter", responseContent, task, currentStep);
        }

        // 4. Streaming timeout check
        if (streamingTimeout) {
            responseContent = applyValidationAction(validation.getOnStreamingTimeout(), "streaming_timeout",
                    "Streaming response timed out", responseContent, task, currentStep);
        }

        // 5. Refusal heuristic — simple check for common refusal patterns
        if (!isNullOrEmpty(responseContent)) {
            String lower = responseContent.trim().toLowerCase();
            if (lower.startsWith("i'm sorry, i can't") || lower.startsWith("i cannot")
                    || lower.startsWith("i'm not able to") || lower.startsWith("as an ai")) {
                responseContent = applyValidationAction(validation.getOnRefusal(), "refusal_detected",
                        "LLM response appears to be a refusal", responseContent, task, currentStep);
            }
        }

        return responseContent;
    }

    /**
     * Applies a single validation action.
     *
     * @return the (possibly modified) response content
     */
    private String applyValidationAction(String action, String validationType, String message,
                                         String responseContent, Task task, IWritableConversationStep currentStep)
            throws LifecycleException {

        if (action == null || "ignore".equalsIgnoreCase(action)) {
            return responseContent;
        }

        switch (action.toLowerCase()) {
            case "warn" :
                LOGGER.warnf("[ResponseValidation] %s (task=%s): %s", validationType, task.getId(), message);
                var warnData = dataFactory.createData("llm:validation:" + validationType + ":" + task.getId(), message);
                currentStep.storeData(warnData);
                break;

            case "fallback" :
                String fallbackMsg = "I'm sorry, I wasn't able to generate a complete response. Please try again.";
                LOGGER.warnf("[ResponseValidation] %s — substituting fallback (task=%s)", validationType, task.getId());
                var fallbackData = dataFactory.createData("llm:validation:" + validationType + ":" + task.getId(),
                        Map.of("action", "fallback", "original", responseContent != null ? responseContent : ""));
                currentStep.storeData(fallbackData);
                return fallbackMsg;

            case "error" :
                LOGGER.errorf("[ResponseValidation] %s — throwing error (task=%s): %s", validationType, task.getId(), message);
                throw new LifecycleException("Response validation failed [" + validationType + "]: " + message);

            default :
                LOGGER.warnf("[ResponseValidation] Unknown action '%s' for %s, treating as 'warn'", action, validationType);
                var unknownData = dataFactory.createData("llm:validation:" + validationType + ":" + task.getId(), message);
                currentStep.storeData(unknownData);
                break;
        }

        return responseContent;
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
        // Same JSON policy the live loop would have applied — a resumed continuation
        // must not silently lose the API-level JSON the paused turn was running with.
        var jsonPolicy = JsonResponseFormatPolicy.of(Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT)), resolvedType,
                task.getJsonResponseFormat());

        // Stream the continuation's model rounds too. Replayed transcript rounds
        // never call the model, so the bridge only ever forwards NEW tokens — text
        // streamed before the pause cannot be re-emitted. The resume path has no
        // single-chunk fallback emit, so no suppression bookkeeping is needed here.
        var resumeBridge = createToolLoopStreamingBridge(memory.getEventSink(),
                "false".equalsIgnoreCase(processedParams.get(KEY_ADD_TO_OUTPUT)), resolvedType, processedParams, task);

        var result = agentOrchestrator.resumeToolLoop(resumeBridge != null ? resumeBridge : chatModel, task, memory, batch, resumeDecision,
                toolHitlEnabled, jsonPolicy);

        String responseContent = result != null ? result.response() : null;
        List<Map<String, Object>> toolTrace = result != null && result.trace() != null ? result.trace() : new ArrayList<>();
        Map<String, Object> responseMetadata = result != null && result.responseMetadata() != null
                ? new HashMap<>(result.responseMetadata())
                : new HashMap<>();

        // === Store the result EXACTLY like the normal path (executeTask) ===
        // Surface metadata the same way executeTask does. Note the continuation's
        // tokenUsage covers only the post-resume model calls: the usage accumulated
        // before the pause dies with ToolApprovalRequiredException and resumeToolLoop
        // starts a fresh accumulator, so a paused turn under-reports by its pre-pause
        // segment.
        var responseMetadataObjectName = task.getResponseMetadataObjectName();
        if (!isNullOrEmpty(responseMetadataObjectName)) {
            templateDataObjects.put(responseMetadataObjectName, responseMetadata);
            prePostUtils.createMemoryEntry(currentStep, responseMetadata, responseMetadataObjectName, KEY_LANGCHAIN);
        }

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
            // LifecycleManager gates the whole llmDetail block on the compiled prompt.
            // Omitting it here dropped model response, model name, token usage and cost
            // from the audit entry of every HITL-resumed turn — precisely the turns a
            // human intervened in, and therefore the ones most worth auditing.
            var compiledPrompt = dataFactory.createData(MemoryKeys.AUDIT_COMPILED_PROMPT,
                    processedParams.getOrDefault(KEY_SYSTEM_MESSAGE, "") + "\n---\n" + processedParams.getOrDefault(KEY_PROMPT, ""));
            currentStep.storeData(compiledPrompt);

            if (responseContent != null) {
                var modelResponse = dataFactory.createData(MemoryKeys.AUDIT_MODEL_RESPONSE, responseContent);
                currentStep.storeData(modelResponse);
            }
            String modelName = processedParams.getOrDefault("model", task.getType());
            var modelNameData = dataFactory.createData(MemoryKeys.AUDIT_MODEL_NAME, modelName);
            currentStep.storeData(modelNameData);

            // Known gap: the continuation's tokenUsage covers only the post-resume model
            // calls (see the comment above) — the pre-pause segment is not recoverable
            // here, so a paused turn's ledger entry under-reports by that segment.
            accumulateAuditEvidence(currentStep, responseMetadata, toolTrace, task);
        }

        // Tool trace (mirror executeTask)
        if (!toolTrace.isEmpty()) {
            var traceData = dataFactory.createData(LANGCHAIN_TRACE_PREFIX + task.getType() + ":" + task.getId(), toolTrace);
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

    /**
     * A vault reference MENTIONED in an LLM parameter — {@code {vault:key-name}},
     * with or without the leading {@code $} (which is plain text to Qute either
     * way).
     */
    private static final Pattern VAULT_REF_MENTION = Pattern.compile("\\{vault:[^}]*\\}");

    /**
     * Wraps {@code {vault:...}} mentions in Qute raw sections so a PROMPT may talk
     * about the syntax without crashing templating.
     * <p>
     * The Platform Operator's system prompt instructs the model to write secrets as
     * {@code ${vault:key-name}} references. Qute parses the brace part as a
     * namespaced expression, and there is deliberately no {@code vault} namespace
     * resolver (see {@code CallerNamespaceResolver}'s class doc: letting vault
     * references survive templating in general would let one ride a templated
     * request BODY into vault resolution, and the resolved body is written to
     * conversation memory in plaintext) — so every turn of such an agent failed
     * templating for that parameter and fell back to the RAW string, skipping every
     * legitimate {@code {memory...}} expression alongside it.
     * <p>
     * Escaping HERE, for LLM parameters only, threads that needle: these values go
     * to the model, never through vault resolution, so a literal
     * {@code ${vault:key-name}} in a prompt is inert documentation. Httpcall
     * templating does not pass through this method and keeps failing loudly,
     * exactly as that security decision requires.
     * <p>
     * Known limit: a mention already inside a {@code {|raw|}} section would be
     * double-wrapped and render its markers. Prompts do not write Qute raw
     * sections; accepting that beats parsing Qute here.
     */
    static String escapeVaultMentions(String value) {
        if (value == null || !value.contains("vault:")) {
            return value;
        }
        return VAULT_REF_MENTION.matcher(value).replaceAll(match -> "{|" + match.group() + "|}");
    }

    private HashMap<String, String> runTemplateEngineOnParams(Map<String, String> parameters, Map<String, Object> templateDataObjects) {

        var processedParams = new HashMap<>(parameters);
        processedParams.forEach((key, value) -> {
            try {
                if (!isNullOrEmpty(value) && !TEMPLATE_SKIP_PARAMS.contains(key)) {
                    processedParams.put(key, templatingEngine.processTemplate(escapeVaultMentions(value), templateDataObjects));
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
     * Finding F13: build the {@link LlmConfiguration.ConversationSummaryConfig} the
     * summarizer should actually run with.
     * <p>
     * When the summary config names no provider/model of its own, the parent task's
     * are inherited — matching how {@link ToolResponseTruncator} builds its
     * summarizer. Returns a fresh copy: the task configuration object is cached and
     * shared across conversations, so it must never be mutated.
     *
     * @return a copy with provider/model resolved, or the original when it already
     *         names both
     */
    static LlmConfiguration.ConversationSummaryConfig resolveEffectiveSummaryConfig(LlmConfiguration.ConversationSummaryConfig configured,
                                                                                    String parentProvider, String parentModel) {
        if (configured == null) {
            return null;
        }
        boolean providerMissing = isNullOrEmpty(configured.getLlmProvider());
        boolean modelMissing = isNullOrEmpty(configured.getLlmModel());
        if (!providerMissing && !modelMissing) {
            return configured;
        }

        var effective = new LlmConfiguration.ConversationSummaryConfig();
        effective.setEnabled(configured.isEnabled());
        effective.setLlmProvider(providerMissing ? parentProvider : configured.getLlmProvider());
        effective.setLlmModel(modelMissing ? parentModel : configured.getLlmModel());
        effective.setMaxSummaryTokens(configured.getMaxSummaryTokens());
        effective.setExcludePropertiesFromSummary(configured.isExcludePropertiesFromSummary());
        effective.setRecentWindowSteps(configured.getRecentWindowSteps());
        effective.setMaxRecallTurns(configured.getMaxRecallTurns());
        effective.setSummarizationPrompt(configured.getSummarizationPrompt());
        return effective;
    }

    /**
     * Coarse-grained predecessor of
     * {@link #resolveInheritedSummaryParameters(Map, String, String)}: it answers
     * the same F13 question — may the parent task's resolved parameters reach the
     * summarizer? — but on a mismatch it drops the map wholesale instead of
     * dropping only the provider-bound keys.
     * <p>
     * The live call site uses
     * {@link #resolveInheritedSummaryParameters(Map, String, String)}, which is
     * strictly better: it isolates credentials and endpoint coordinates while
     * letting vendor-neutral tuning (temperature, maxTokens, timeout, …) carry
     * over, so a cross-provider summary config still honours the task's tuning.
     * This method is retained only because it is still directly asserted on by
     * {@code LlmTaskPromptBoundsTest}; it is not on any production path.
     *
     * @return {@code processedParams} for the same-provider case, {@code null} when
     *         the summary config names a different provider (it must then carry its
     *         own credentials, which {@code ChatModelRegistry} resolves from the
     *         provider defaults)
     */
    static Map<String, String> inheritableSummaryParameters(LlmConfiguration.ConversationSummaryConfig effective, String parentProvider,
                                                            Map<String, String> processedParams, String conversationId) {
        if (effective == null) {
            return null;
        }
        String summaryProvider = effective.getLlmProvider();
        if (isNullOrEmpty(summaryProvider) || isNullOrEmpty(parentProvider)
                || summaryProvider.trim().equalsIgnoreCase(parentProvider.trim())) {
            return processedParams;
        }
        LOGGER.infof("[SUMMARY] conversationSummary.llmProvider='%s' differs from the task provider '%s' for conversation '%s' — "
                + "the parent task's credentials are NOT inherited; the summary config must carry its own apiKey/baseUrl.",
                summaryProvider, parentProvider, sanitize(conversationId));
        return null;
    }

    /**
     * Parameter keys that belong to the provider that issued them: credentials and
     * the endpoint coordinates that address that provider's account. Everything
     * else (temperature, maxTokens, timeout, …) is vendor-neutral and safe to carry
     * across a provider boundary.
     */
    private static final Set<String> PROVIDER_BOUND_PARAMETERS = Set.of(
            "apiKey", "accessToken", "authToken", "nonAzureApiKey", "signingSecret", "appPassword", "botToken",
            "baseUrl", "endpoint", "deploymentName",
            "compartmentId", "configProfile", "projectId", "region", "location");

    /**
     * The second half of the F13 inheritance decision: <em>which</em> of the parent
     * task's resolved parameters may travel to the summarizer.
     * <p>
     * {@link #resolveEffectiveSummaryConfig} deliberately honours a
     * {@code conversationSummary.llmProvider} that names a <em>different</em>
     * vendor than the parent task. The parameter map, however, is the parent
     * provider's: inheriting it wholesale would hand one vendor's plaintext
     * {@code apiKey} (the vault reference is expanded downstream by
     * {@code ChatModelRegistry}) to another vendor's endpoint, and an inherited
     * {@code baseUrl} would redirect the summarization request — which carries the
     * condensed transcript — at the parent provider's host. Sibling inheritance in
     * {@code ToolResponseTruncator} cannot hit this because it always builds its
     * summarizer with the parent's own type.
     * <p>
     * So credentials and endpoint coordinates are inherited only when the
     * summarizer runs on the parent's provider. On a mismatch they are dropped (the
     * summary provider must supply its own, e.g. via a global variable or a
     * vault-backed deployment default) while the vendor-neutral tuning parameters
     * still carry over.
     *
     * @param parentParameters
     *            the parent task's resolved parameters; may be null
     * @param parentProvider
     *            the parent task's resolved provider type
     * @param summaryProvider
     *            the provider the summarizer will actually run on
     * @return the parameters safe to inherit, or {@code null} when there are none
     */
    static Map<String, String> resolveInheritedSummaryParameters(Map<String, String> parentParameters, String parentProvider,
                                                                 String summaryProvider) {
        if (parentParameters == null || parentParameters.isEmpty()) {
            return parentParameters;
        }
        if (!isNullOrEmpty(parentProvider) && !isNullOrEmpty(summaryProvider)
                && parentProvider.trim().equalsIgnoreCase(summaryProvider.trim())) {
            return parentParameters;
        }

        var safeParameters = new LinkedHashMap<String, String>();
        var dropped = new ArrayList<String>();
        parentParameters.forEach((key, value) -> {
            if (key != null && PROVIDER_BOUND_PARAMETERS.stream().anyMatch(key::equalsIgnoreCase)) {
                dropped.add(key);
            } else {
                safeParameters.put(key, value);
            }
        });

        if (!dropped.isEmpty()) {
            LOGGER.warnf("[SUMMARY] conversationSummary runs on provider '%s' while the task runs on '%s' — not inheriting %s. "
                    + "Credentials must never cross a provider boundary; configure them for '%s' "
                    + "(global variable or vault-backed default), or omit llmProvider to reuse the task's model.",
                    sanitize(summaryProvider), sanitize(parentProvider), dropped, sanitize(summaryProvider));
        }
        return safeParameters;
    }

    /**
     * Finding F10: a tool-enabled turn that did NOT stream token-by-token pushes
     * its whole answer through a single {@code onToken} — a long silence followed
     * by one enormous token event, indistinguishable from a slow stream. Since the
     * {@link ToolLoopStreamingChatModel} bridge, the non-cascade agent paths
     * usually stream live and skip this record; it still fires whenever the
     * single-chunk fallback runs — kill-switch off, no streaming builder, a
     * buffered provider, a JSON-formatted final round, a synthetic iteration-budget
     * message, and the whole cascade agent path.
     * <p>
     * Record the downgrade so it is observable: a {@code streamingDowngraded} flag
     * in {@code responseMetadata} (surfaced through
     * {@code responseMetadataObjectName}, so an agent designer can branch on it)
     * and an {@code eddi.llm.streaming.downgraded} counter.
     */
    private void recordStreamingDowngrade(Map<String, Object> responseMetadata, LlmConfiguration.Task task, String responseContent) {
        if (responseMetadata != null) {
            responseMetadata.put("streamingDowngraded", true);
            responseMetadata.put("streamingDowngradeReason", "tools_enabled");
        }
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.streaming.downgraded", "reason", "tools_enabled").increment();
        }
        LOGGER.infof("Streaming downgraded to a single chunk for task '%s': the tool-calling loop is synchronous (%d chars emitted at once)",
                task.getId(), responseContent.length());
    }

    /**
     * The agent-mode leg shared verbatim by the skipCascade and standard branches
     * (extracted so the two cannot drift): build the streaming bridge, run the tool
     * loop with it, package the outcome and emit the final response unless it
     * already streamed live. Returns null when the task has no tools — the caller
     * then runs its own legacy fallback, which must NEVER see the bridge
     * ({@code executeIfToolsEnabled} returns before any model call in that case, so
     * no token has been forwarded; handing the bridge to a legacy executor would
     * double-emit every token).
     */
    private AgentModeOutcome runToolLoopIfEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessagesWithoutSystem,
                                                  LlmConfiguration.Task task, IConversationMemory memory,
                                                  ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex,
                                                  JsonResponseFormatPolicy jsonPolicy, ConversationEventSink eventSink,
                                                  boolean addToOutputExplicitlyFalse, String resolvedType,
                                                  Map<String, String> processedParams)
            throws LifecycleException, ChatModelRegistry.UnsupportedLlmTaskException {
        var toolLoopBridge = createToolLoopStreamingBridge(eventSink, addToOutputExplicitlyFalse, resolvedType, processedParams, task);
        var agentResult = agentOrchestrator.executeIfToolsEnabled(toolLoopBridge != null ? toolLoopBridge : chatModel, systemMessage,
                new ArrayList<>(chatMessagesWithoutSystem), task,
                memory, effectiveToolApprovals, llmTaskIndex, toolTranscriptMaxBytes, jsonPolicy);
        if (agentResult == null) {
            return null;
        }
        String responseContent = agentResult.response();
        // Null-guarded to match executeResume. No production path returns a null
        // trace today (both ExecutionResult sites pass a fresh list), but the
        // record does not enforce it and toolTrace.isEmpty() would NPE later.
        List<Map<String, Object>> trace = agentResult.trace() != null ? agentResult.trace() : new ArrayList<>();
        // AgentOrchestrator sums TokenUsage across every model call in the tool
        // loop and returns it here; not reading it dropped all agent-mode token
        // accounting on the floor while the legacy branches kept theirs.
        // Copied, not aliased: ExecutionResult's two-arg constructor yields an
        // immutable Map.of(), so assigning it directly would make any later
        // metadata write throw only on the agent path, only in production.
        Map<String, Object> responseMetadata = new HashMap<>(agentResult.responseMetadata());
        if (eventSink != null && responseContent != null && !addToOutputExplicitlyFalse) {
            emitAgentResponseUnlessStreamedLive(eventSink, responseContent, responseMetadata, task, toolLoopBridge);
        }
        return new AgentModeOutcome(responseContent, trace, responseMetadata);
    }

    /** What the tool loop produced, ready for the caller's four locals. */
    private record AgentModeOutcome(String response, List<Map<String, Object>> trace, Map<String, Object> responseMetadata) {
    }

    /**
     * Build the streaming bridge for a tool-enabled turn, or return null when the
     * turn should keep the synchronous single-chunk path: kill-switch off, no event
     * sink, output suppressed ({@code addToOutput=false} — the postResponse owns
     * the output, live tokens would leak the raw response), or the provider has no
     * streaming builder.
     */
    private ToolLoopStreamingChatModel createToolLoopStreamingBridge(ConversationEventSink eventSink, boolean addToOutputExplicitlyFalse,
                                                                     String resolvedType, Map<String, String> processedParams,
                                                                     LlmConfiguration.Task task)
            throws ChatModelRegistry.UnsupportedLlmTaskException {
        if (!toolLoopStreamingEnabled || eventSink == null || addToOutputExplicitlyFalse) {
            return null;
        }
        var streamingModel = chatModelRegistry.getOrCreateStreaming(resolvedType, processedParams);
        if (streamingModel == null) {
            return null;
        }
        return new ToolLoopStreamingChatModel(streamingModel, eventSink,
                StreamingLegacyChatExecutor.resolveTimeoutSeconds(task), resolvedType);
    }

    /**
     * Emit an agent-mode final response as a single chunk — unless the streaming
     * bridge already delivered exactly this text token-by-token, in which case
     * emitting it again would duplicate the whole answer on the client.
     * <p>
     * The comparison is an exact text match against the bridge's last completed
     * model round, not a "did anything stream" boolean, because the final response
     * is not always that round's text: an exhausted iteration budget substitutes a
     * synthetic message, a JSON-formatted round forwards nothing, and a provider
     * that buffers (never emits partials) completes without streaming a character.
     * In all those cases the single-chunk fallback below still runs — and still
     * counts as a downgrade, because that is what the client experienced.
     */
    private void emitAgentResponseUnlessStreamedLive(ConversationEventSink eventSink, String responseContent,
                                                     Map<String, Object> responseMetadata, LlmConfiguration.Task task,
                                                     ToolLoopStreamingChatModel toolLoopBridge) {
        if (toolLoopBridge != null && responseContent.equals(toolLoopBridge.lastForwardedText())) {
            responseMetadata.put("streamedLive", true);
            return;
        }
        recordStreamingDowngrade(responseMetadata, task, responseContent);
        eventSink.onToken(responseContent);
    }

    /**
     * Finding F7: cap a RAG context block before it is appended to the system
     * prompt.
     * <p>
     * {@code toolResponseLimits} governs tool results inside the agent loop; it has
     * never applied to the httpCall-RAG response, which was serialized and appended
     * whole. {@code maxContextTokens} covers conversation history only, explicitly
     * not the system prompt.
     *
     * @param context
     *            the retrieved context (may be null)
     * @param maxChars
     *            character ceiling; {@code -1} = unbounded
     * @param source
     *            human-readable source name, used in the warning
     * @return the context, truncated with an explanatory marker when it exceeded
     *         the cap
     */
    static String capRagContext(String context, int maxChars, String source) {
        if (context == null || maxChars <= 0 || context.length() <= maxChars) {
            return context;
        }
        LOGGER.warnf("%s produced %d chars of context, capped at %d (maxRagContextChars). "
                + "Narrow the retrieval or raise the cap.", source, context.length(), maxChars);
        return context.substring(0, maxChars) + "\n[... context truncated at " + maxChars + " characters ...]";
    }

    /**
     * Finding F7: hard ceiling on the fully assembled system prompt.
     * {@code maxChars <= 0} leaves the prompt untouched (the default).
     */
    static String capSystemPrompt(String systemMessage, Integer maxChars, String taskId) {
        if (systemMessage == null || maxChars == null || maxChars <= 0 || systemMessage.length() <= maxChars) {
            return systemMessage;
        }
        LOGGER.warnf("System prompt for task '%s' is %d chars, capped at %d (maxSystemPromptChars).", taskId, systemMessage.length(), maxChars);
        return systemMessage.substring(0, maxChars) + "\n[... system prompt truncated at " + maxChars + " characters ...]";
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
        var stepConfigs = WorkflowTraversal.discoverConfigs(memory, HTTPCALLS_TYPE, ApiCallsConfiguration.class, agentStore, workflowStore,
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

                        // A FAILED retrieval contributes nothing. Its result now
                        // carries the error body (so LLM TOOLS can report
                        // failures), but this path pastes the serialized result
                        // into the SYSTEM prompt as "## Search Results" — where
                        // up to 2KB of proxy/WAF error page, attacker-influenced
                        // in some architectures, would masquerade as retrieved
                        // knowledge. Pre-contract, a failed call contributed an
                        // empty map here; keep that meaning.
                        if (result.get("httpCode") instanceof Integer code && (code < 200 || code >= 300)) {
                            LOGGER.warnf("httpCall RAG '%s' returned %d — omitting from context", httpCallName, code);
                            return null;
                        }

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
                LlmConfiguration llmConfiguration = resourceClientLibrary.getResource(uri, LlmConfiguration.class);
                // Fail fast on cascade misconfiguration at deploy time (#validation).
                CascadeConfigValidator.validate(llmConfiguration);
                return llmConfiguration;
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
     * <p>
     * Public rather than private so {@code ToolContextBudget}
     * ({@code ai.labs.eddi.modules.llm.impl.orchestration}, extracted from
     * {@code AgentOrchestrator} in R2 step 3) can resolve the same model name when
     * it picks a token estimator for the in-turn tool-context budget — one
     * resolution table, not two that can drift.
     */
    public static String resolveModelName(Map<String, String> processedParams) {
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

    /**
     * Whether an LLM response should become a rendered message bubble.
     * <p>
     * A turn can legitimately produce no text — token budget exhaustion, or a
     * thinking-only turn — and those must not surface as empty bubbles. Extracted
     * so the rule is asserted against the code that runs rather than against a copy
     * of the expression re-typed in a test.
     *
     * @param responseContent
     *            the model's text response (may be null)
     * @return true when there is something worth showing the user
     */
    static boolean producesRenderableOutput(String responseContent) {
        return responseContent != null && !responseContent.isBlank();
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
