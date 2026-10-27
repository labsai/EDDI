/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ResourceUris;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.shared.LlmFailureClassifier;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.TaskToolApprovalsResolver;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.IAnswerRevisingTask;
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
import ai.labs.eddi.modules.llm.governance.ToolResultProvenance;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailOutcome;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.guardrails.impl.JudgeGuardrail;
import ai.labs.eddi.modules.llm.guardrails.Reprompter;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleEndpoint;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProvider;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProviders;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.ConfigReferenceGuard;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionRequest;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
public class LlmTask implements ILifecycleTask, IAnswerRevisingTask {
    public static final String ID = "ai.labs.llm";
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final String KEY_URI = "uri";
    private static final String KEY_LANGCHAIN = "langchain";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";
    private static final String KEY_INCLUDE_FIRST_AGENT_MESSAGE = "includeFirstAgentMessage";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    /** Step key prefix for the convertToObject parse outcome (task id appended). */
    static final String KEY_OUTPUT_OUTCOME = "llm:output:outcome:";
    /**
     * Step data {@code llm:retry:<taskId>}: {@code {reasks, unresolved}} of a
     * format recovery.
     */
    static final String KEY_RETRY = "llm:retry:";
    /** Why the outcome is not valid ({@code invalid}, {@code schema_mismatch}). */
    static final String KEY_OUTPUT_REASON = "llm:output:reason:";
    /**
     * Response-metadata key of a cascade guardrail reprompt's dollar cost, priced
     * with the winning step's prices (the cascade carries cost, not tokens, into
     * the ledger).
     */
    private static final String KEY_REPROMPT_COST = "guardrailRepromptCostUsd";
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
    private final ModelOutputParser modelOutputParser;
    private final PrePostUtils prePostUtils;

    private final ChatModelRegistry chatModelRegistry;
    private final LlmFallbackHandler fallbackHandler;
    private final ConversationHistoryBuilder conversationHistoryBuilder;
    private final LegacyChatExecutor legacyChatExecutor;
    private final StreamingLegacyChatExecutor streamingLegacyChatExecutor;
    private final CascadingModelExecutor cascadingModelExecutor;
    private final FormatRetryRunner formatRetryRunner;
    /**
     * The per-model circuit breakers (R8). Initializer-injected, so the many
     * direct-construction unit tests are unaffected; null leaves every task
     * unguarded, which is also what a task without {@code circuitBreaker.enabled}
     * gets.
     */
    private LlmCircuitBreakers circuitBreakers;

    /**
     * The pre-model-call spend gate (the agent's per-conversation cost ceiling and
     * the tenant monthly budget). Field-injected, so the many direct-construction
     * unit tests are unaffected; null leaves every task ungated.
     */
    @Inject
    LlmSpendGate spendGate;
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

    /**
     * Input/output guardrails (WS4). Field-injected for the same reason as
     * {@link #attachmentForwarder}; null-guarded, and only touched by a task that
     * configures {@code inputGuardrails} or {@code outputGuardrails} — a task
     * without them never reaches the bean.
     */
    @Inject
    GuardrailService guardrailService;

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

    /**
     * Cap (bytes) of the frozen request an answer keeps for a human revision
     * ({@code hitlConfig.onRejection: REVISE}). It is stored in the paused step, so
     * it is far below the tool-pause cap; an answer over it has nothing to revise
     * and a rejection skips as under SKIP. Initialized for the direct-construction
     * unit tests.
     */
    @Inject
    @ConfigProperty(name = "eddi.hitl.revision.transcript-max-bytes", defaultValue = "262144")
    int revisionTranscriptMaxBytes = 262_144;

    private final ChatTranscriptCodec revisionTranscriptCodec = new ChatTranscriptCodec();

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
        this.modelOutputParser = new ModelOutputParser(jsonSerialization);
        this.fallbackHandler = new LlmFallbackHandler(templatingEngine, jsonSerialization, dataFactory, meterRegistry);
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
        this.formatRetryRunner = new FormatRetryRunner(modelOutputParser, meterRegistry);
        this.cascadingModelExecutor = new CascadingModelExecutor(chatModelRegistry, globalVariableResolver, templatingEngine, legacyChatExecutor,
                streamingLegacyChatExecutor, meterRegistry, callerIdentityContext, formatRetryRunner);
    }

    @Inject
    void setCircuitBreakers(LlmCircuitBreakers circuitBreakers) {
        this.circuitBreakers = circuitBreakers;
        this.cascadingModelExecutor.setCircuitBreakers(circuitBreakers);
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

            var templateDataObjects = templateData(memory);

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

        } catch (ToolApprovalRequiredException pause) {
            // A tool loop paused for approval. What it spent up to here really happened
            // but no result will carry it, so record it on the step now: the pause
            // snapshot persists the step, the pause's settle meters it, and the resume
            // counts it once through audit:cost — in its spend preflight, its loop
            // ceiling and its total. Recorded here and only here, for the live path and
            // a resume that pauses again alike.
            recordPrePauseSpend(memory.getCurrentStep(), pause);
            throw pause;
        } catch (ITemplatingEngine.TemplateEngineException | ChatModelRegistry.UnsupportedLlmTaskException | IOException | LifecycleException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * The template data every task renders against: the memory, plus the prompt
     * snippets this agent may use ({@code {snippets.<name>}}, scoped to the agent's
     * workspace: getAll() would hand every workspace's snippets to every agent, and
     * let a same-named snippet elsewhere replace this one's) and the global
     * variables ({@code {vars.<key>}}).
     */
    private Map<String, Object> templateData(IConversationMemory memory) {
        var templateDataObjects = memoryItemConverter.convert(memory);
        Map<String, Object> snippets = promptSnippetService.getForAgent(memory.getAgentId());
        if (!snippets.isEmpty()) {
            templateDataObjects.put("snippets", snippets);
        }
        Map<String, Object> globalVars = globalVariableResolver.getTemplateData();
        if (!globalVars.isEmpty()) {
            templateDataObjects.put("vars", globalVars);
        }
        return templateDataObjects;
    }

    /**
     * Folds a pausing run's spend so far into the step's {@code audit:token_usage}
     * and {@code audit:cost} (see {@link ToolApprovalRequiredException#addSpend}).
     */
    private void recordPrePauseSpend(IWritableConversationStep currentStep, ToolApprovalRequiredException pause) {
        if (currentStep == null) {
            return;
        }
        accumulateTokenUsage(currentStep, pause.getTokenUsageSoFar());
        accumulateCost(currentStep, pause.getCostSoFarUsd());
    }

    /**
     * Execute a single task — delegates to LegacyChatExecutor or AgentOrchestrator.
     */
    private void executeTask(IConversationMemory memory, Task task, IWritableConversationStep currentStep, Map<String, Object> templateDataObjects,
                             int llmTaskIndex)
            throws ITemplatingEngine.TemplateEngineException, ChatModelRegistry.UnsupportedLlmTaskException, IOException, LifecycleException {

        // onRejection: REVISE — where this task's output begins, so a revision can
        // cut back what the draft (and the tasks that run again after it) wrote.
        final int revisionOutputMark = outputListSize(currentStep, MEMORY_OUTPUT_IDENTIFIER);
        final int revisionQuickRepliesMark = outputListSize(currentStep, MemoryKeys.QUICK_REPLIES_PREFIX);

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

        var processedParams = runTemplateEngineOnParams(task.getParameters(), templateDataObjects, memory);

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

        // Spend gate: asked once, before anything of this task that costs money (an
        // input guardrail may call a classifier model, retrieval an embedding
        // provider) and before any model request (plain, tool loop or cascade). A
        // refusal skips all of it and serves the task's fallback answer — see the
        // SpendRefusedException catch. The input is not screened then: nothing of it
        // reaches a model or a retrieval query.
        LlmSpendGate.Refusal spendRefusal = spendGate != null ? spendGate.check(memory) : null;

        // === Input guardrails, first pass (WS4) ===
        // The user's raw message is screened BEFORE retrieval: a blocked message issues
        // no retrieval query and no httpCall, and a redacted one is what the RAG query
        // (possibly an external embedding provider) sees. The model-facing message is
        // assembled later; when it is still exactly this text the second pass below
        // reuses this result instead of evaluating again.
        boolean screenInput = spendRefusal == null && guardrailService != null && !isNullOrEmpty(task.getInputGuardrails());
        if (spendRefusal != null && guardrailService != null && guardrailService.anySkipsHistory(task.getInputGuardrails())) {
            // Never screened, so a guardrail that does not re-screen history (the
            // classifier) would never see this message: withhold it from later turns.
            markInputWithheld(currentStep, task);
        }
        GuardrailService.TextResult inputPrescreen = null;
        if (screenInput && userInput != null) {
            inputPrescreen = guardrailService.screenInput(task.getInputGuardrails(), userInput,
                    guardrailContext(memory, task, List.of()).withSpend(TaskSpend.of(spendGate, memory)),
                    outcome -> recordGuardrailOutcome(memory, currentStep, task, outcome));
            accumulateCost(currentStep, inputPrescreen.costUsd());
            if (inputPrescreen.isBlocked()) {
                answerInputBlocked(memory, currentStep, task, processedParams, templateDataObjects, inputPrescreen.blockMessage());
                return;
            }
            userInput = inputPrescreen.text();
        }

        // Finding F7: maxContextTokens explicitly excludes the system prompt, so
        // nothing bounded the RAG blocks appended to it. Both appends below are capped
        // by the task's maxRagContextChars.
        int maxRagContextChars = RagContextProvider.resolveMaxChars(task);
        // What retrieval returned this turn, for an output guardrail that checks the
        // answer's groundedness (classifier, includeRetrievedContext).
        StringBuilder retrievedContext = new StringBuilder();

        // Phase 8c-0: Zero-infrastructure RAG via named httpCall
        String httpCallRag = task.getHttpCallRag();
        if (spendRefusal == null && !isNullOrEmpty(httpCallRag) && userInput != null) {
            try {
                String httpCallContext = capRagContext(executeHttpCallRag(memory, httpCallRag, userInput, templateDataObjects), maxRagContextChars,
                        "httpCall RAG '" + httpCallRag + "'");
                if (httpCallContext != null) {
                    retrievedContext.append(httpCallContext);
                    systemMessage += "\n\n## Search Results:\n" + markRetrievedContext(task, "httpcall:" + httpCallRag, httpCallContext);
                    LOGGER.debugf("httpCall RAG context injected for task '%s': %d chars", taskId, httpCallContext.length());
                    var traceData = dataFactory.createData("rag:httpcall:trace:" + taskId,
                            Map.of("httpCall", httpCallRag, "contextLength", httpCallContext.length()));
                    currentStep.storeData(traceData);
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "httpCall RAG failed for '%s': %s", httpCallRag, e.getMessage());
            }
        }

        // Vector store RAG: retrieve from knowledge bases in the workflow
        if (spendRefusal == null && userInput != null) {
            try {
                String ragContext = capRagContext(ragContextProvider.retrieveContext(memory, task, userInput), maxRagContextChars,
                        "vector RAG for task '" + taskId + "'");
                if (ragContext != null) {
                    if (!retrievedContext.isEmpty()) {
                        retrievedContext.append("\n\n");
                    }
                    retrievedContext.append(ragContext);
                    systemMessage += "\n\n## Relevant Context:\n" + markRetrievedContext(task, "knowledge-base", ragContext);
                    LOGGER.debugf("RAG context injected for task '%s': %d chars", taskId, ragContext.length());
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
        systemMessage = counterweightService.apply(systemMessage, task.getCounterweight(), channelTag, memory.getAgentId());

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
                LOGGER.debugf("[SUMMARY] Using rolling summary for task '%s': covers turns 1-%d, recent window from step %d", taskId, throughStep,
                        throughStep + 1);
            }
        }

        // Resolve global variable references in task type BEFORE any model
        // lookups — used by token estimator, sync model, and streaming model.
        var resolvedType = globalVariableResolver.resolveValue(task.getType());

        List<ChatMessage> messages;
        if (maxContextTokens != null && maxContextTokens > 0) {
            // Resolve model name from provider-specific parameter keys
            String resolvedModelName = resolveModelNameOf(processedParams, resolvedType);
            var estimator = tokenCounterFactory.getEstimator(resolvedType, resolvedModelName);
            messages = conversationHistoryBuilder.buildTokenAwareMessages(memory, systemMessage, processedParams.get(KEY_PROMPT), maxContextTokens,
                    anchorFirstSteps, includeFirstAgentMessage, estimator, summaryPrefix, skipSteps);
        } else {
            // Legacy step-count windowing
            messages = conversationHistoryBuilder.buildMessages(memory, systemMessage, processedParams.get(KEY_PROMPT), logSizeLimit,
                    includeFirstAgentMessage, summaryPrefix, skipSteps);
        }

        // Where the user's typed text sits in the current message, taken before the
        // attachments append their own text parts behind it (input guardrails screen
        // both, and must not mistake one for the other).
        int typedTextIndex = GuardrailService.currentTextIndex(messages);
        // Forward the current step's attachments to the LLM as multimodal content,
        // gated on the resolved (provider, model) capabilities, honoring any
        // per-task multimodal overrides.
        forwardAttachments(messages, memory, task, resolvedType, processedParams);

        if (messages.isEmpty()) {
            return;
        }

        // === Input guardrails, second pass (WS4) ===
        // On the assembled prompt (RAG, counterweight, identity masking, history,
        // attachments), before the model is built, preRequest runs or the circuit is
        // consulted: a blocked message costs no model call and mutates no property. The
        // current message reuses the first pass when unchanged; the earlier user
        // messages of the window are screened again. Redaction rewrites what the model
        // sees; conversation memory keeps the message as the user sent it.
        GuardrailService.InputResult inputScreening = null;
        if (screenInput) {
            inputScreening = guardrailService.runInput(task.getInputGuardrails(), messages,
                    guardrailContext(memory, task, messages).withSpend(TaskSpend.of(spendGate, memory)),
                    outcome -> recordGuardrailOutcome(memory, currentStep, task, outcome), inputPrescreen, typedTextIndex);
            accumulateCost(currentStep, inputScreening.costUsd());
            if (inputScreening.isBlocked()) {
                answerInputBlocked(memory, currentStep, task, processedParams, templateDataObjects, inputScreening.blockMessage());
                return;
            }
            messages = inputScreening.messages();
            if (inputScreening.withholdFromHistory()) {
                // Redacted by a guardrail that later turns do not re-run over their
                // history: withhold the message there instead. Only now — this turn's
                // prompt is already built from memory.
                markInputWithheld(currentStep, task);
            }
        }
        final List<ChatMessage> requestMessages = messages;
        final GuardrailService.InputResult screenedInput = inputScreening;
        final String retrievedContextText = retrievedContext.toString();

        // R5: the same-model recovery policy of the plain (non-cascade) paths, and the
        // way to rebuild a smaller prompt for onContextTooLong. The cascade derives its
        // own policy per step (a step may override maxFormatRetries). A rebuilt prompt
        // gets the input guardrails' rewrites again — it is built from memory, which
        // holds the messages as the user sent them.
        final String shrinkSystemMessage = systemMessage;
        final String shrinkSummaryPrefix = summaryPrefix;
        final int shrinkSkipSteps = skipSteps;
        final int shrinkLogSizeLimit = logSizeLimit;
        FormatRetryRunner.ContextShrinker contextShrinker = () -> reapplyInputScreening(memory, task, screenedInput,
                rebuildWithHalvedWindow(memory, task, shrinkSystemMessage, processedParams.get(KEY_PROMPT), shrinkLogSizeLimit, maxContextTokens,
                        anchorFirstSteps, includeFirstAgentMessage, resolvedType, processedParams, shrinkSummaryPrefix, shrinkSkipSteps));
        // A re-ask is checked against the conversation's ceiling and the tenant budget
        // before it starts, like the first call (reaskSpend).
        final FormatRetryRunner.ReaskSpend reaskSpend = FormatRetryRunner.ReaskSpend.of(spendGate, memory);
        FormatRetryRunner.Policy retryPolicy = withSpend(FormatRetryRunner.Policy.from(task.getResponseValidation(), null,
                Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT)),
                FormatRetryRunner.resolveBaseMaxOutputTokens(processedParams, resolvedType), task.getInputPricePer1M(), task.getOutputPricePer1M(),
                processedParams.get(KEY_RESPONSE_SCHEMA), task.getNonBlankFields(), task.getId()), reaskSpend);
        FormatRetryRunner.ReaskListener retryListener = LlmRecoveryLog.reaskListener(memory.getConversationId(), memory.getAgentId(),
                (trigger, attempt) -> {
                    if (memory.getEventSink() != null) {
                        memory.getEventSink().onLlmRetry(trigger.label(), attempt);
                    }
                });

        // WS4: an output guardrail that can change the answer (block, redact,
        // reprompt) buffers it exactly like a task that may re-ask (R5) — tokens
        // already streamed cannot be taken back. A warn-only guardrail streams.
        boolean outputGuarded = guardrailService != null && !isNullOrEmpty(task.getOutputGuardrails());
        boolean guardrailBuffering = outputGuarded && GuardrailService.requiresBuffering(task.getOutputGuardrails());
        boolean bufferOutput = retryPolicy != null || guardrailBuffering;
        // How an output guardrail's reprompt asks the model again — set by the branch
        // that produced the answer, so the reprompt runs on the same model and never
        // re-runs a tool. Null leaves a reprompt nothing to do but block.
        Reprompter outputReprompter = null;
        // The request that produced the answer, for a human revision after a later
        // rule pause (onRejection: REVISE); set by the branch that produced it.
        List<ChatMessage> revisionBase = null;
        boolean revisionToolTranscript = false;
        Integer revisionCascadeStep = null;

        // Determine whether the multi-model cascade will run. The base model is only
        // needed outside the active-cascade branch, so create it lazily to avoid an
        // unused model build when the cascade owns the request.
        var cascadeConfig = task.getModelCascade();
        boolean cascadeConfigured = cascadeConfig != null && cascadeConfig.isEnabled() && cascadeConfig.getSteps() != null
                && !cascadeConfig.getSteps().isEmpty();
        boolean skipCascade = cascadeConfigured && task.isAgentMode() && !cascadeConfig.isEnableInAgentMode();
        boolean cascadeActive = cascadeConfigured && !skipCascade;

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
        // An openai-compatible endpoint's own capability flags decide for it.
        var jsonPolicy = JsonResponseFormatPolicy.of(jsonMode, resolvedType, task.getJsonResponseFormat(),
                processedParams.get(KEY_RESPONSE_SCHEMA), capabilityParams(resolvedType, processedParams));

        // Execute: try agent mode first, fall back to legacy
        String responseContent;
        Map<String, Object> responseMetadata = new HashMap<>();
        List<Map<String, Object>> toolTrace = new ArrayList<>();
        boolean usedToolMode = false;
        // Real model name of the cascade-selected step, for the audit ledger (#5).
        String cascadeAuditModel = null;

        // Agent mode: the orchestrator adds the system message itself, so hand it the
        // system message the history builder actually emitted — WITH the rolling
        // summary — and strip only that leading message. Passing the pre-summary
        // prompt and filtering every SystemMessage used to drop the summary, all the
        // turns it covered (skipSteps had already cut them from the history), the
        // windowing gap marker and system-role log parts.
        String agentSystemMessage = ConversationHistoryBuilder.composeSystemMessage(systemMessage, summaryPrefix);
        List<ChatMessage> chatMessagesWithoutSystem = ConversationHistoryBuilder.withoutLeadingSystemMessage(messages, agentSystemMessage);

        // === onError: fallback (R7) ===
        // The model phase below — model construction, preRequest, the cascade / tool
        // loop
        // / plain chat — runs under one guard. With onError=fallback a failure that is
        // not control flow is absorbed: the turn gets the configured fallback as its
        // model answer and everything after (storing, output, postResponse) runs as
        // usual. With the default (error) the guard is transparent and the exception
        // propagates exactly as before.
        boolean fallbackOnError = task.getOnError() != null && task.getOnError().isFallback();
        Map<String, IData<?>> dataBefore = fallbackOnError ? snapshotStepData(currentStep) : Map.of();
        LlmFallbackHandler.Fallback fallbackServed = null;
        boolean convertObject = Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT));
        // Asked again once the input guardrails have run: a classifier call is priced,
        // and the model call that follows must not overshoot a conversation ceiling
        // that call reached.
        if (spendRefusal == null && screenInput && spendGate != null) {
            spendRefusal = spendGate.check(memory);
        }
        // R8: the circuit of the task's single model. A cascade guards each of its
        // steps itself, so this is only for the non-cascade paths. Not acquired when
        // the model will not be called: an unsettled half-open probe would block it.
        boolean circuitGuarded = spendRefusal == null && !cascadeActive && circuitBreakers != null && task.getCircuitBreaker() != null
                && task.getCircuitBreaker().isEnabled();
        var circuitKey = circuitGuarded
                ? new LlmCircuitBreakers.Key(memory.getAgentId(), memory.getAgentVersion(), resolvedType,
                        resolveModelNameOf(processedParams, resolvedType))
                : null;
        final LlmCircuitBreakers.Ticket circuitTicket = circuitGuarded
                ? circuitBreakers.acquire(circuitKey, task.getCircuitBreaker())
                : LlmCircuitBreakers.Ticket.DISABLED;
        FormatRetryRunner.ReaskGate circuitGate = circuitTicket::reaskAllowed;
        // A guardrail reprompt is another call to the same model: it asks the breaker
        // first and settles its own ticket, exactly like the primary call (R8).
        RepromptCircuit repromptCircuit = circuitGuarded
                ? new RepromptCircuit(() -> circuitBreakers.acquire(circuitKey, task.getCircuitBreaker()), processedParams, convertObject)
                : RepromptCircuit.NONE;
        try {
            if (spendRefusal != null) {
                throw new LlmSpendGate.SpendRefusedException(spendRefusal);
            }
            if (!circuitTicket.allowed()) {
                recordRecovery(memory, LlmRecoveryLog.CIRCUIT_SKIP, "skipped", circuitTicket.denial().failure().label(), 0L);
                // Nothing else can serve this task: onError=fallback absorbs it below, the
                // default fails the turn fast with the class that opened the circuit.
                throw new LlmCircuitOpenException(circuitTicket.denial().message(circuitKey), circuitTicket.denial().failure());
            }
            var chatModel = cascadeActive ? null : chatModelRegistry.getOrCreate(resolvedType, processedParams);
            prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.getPreRequest());

            // === Multi-Model Cascade Branch ===
            if (cascadeActive) {
                boolean convertToObject = Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT));
                boolean allowLiveStreaming = eventSink != null && !addToOutputExplicitlyFalse && !guardrailBuffering;
                var cascadeResult = cascadingModelExecutor.execute(cascadeConfig, messages, agentSystemMessage, processedParams, task, memory,
                        agentOrchestrator, templateDataObjects, jsonMode, convertToObject, allowLiveStreaming,
                        effectiveToolApprovals, llmTaskIndex, toolTranscriptMaxBytes, contextShrinker,
                        turnBudget(memory.getTurnDeadline()), reaskSpend);

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
                if (eventSink != null && !bufferOutput && responseContent != null && !addToOutputExplicitlyFalse
                        && !cascadeResult.streamedLive()) {
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

                if (outputGuarded) {
                    outputReprompter = cascadeReprompter(cascadeConfig, cascadeResult, task, processedParams, templateDataObjects, memory,
                            jsonMode, agentSystemMessage, chatMessagesWithoutSystem, requestMessages);
                }
                revisionToolTranscript = cascadeResult.agentResult() != null;
                revisionBase = revisionToolTranscript
                        ? CascadingModelExecutor.toolTranscript(agentSystemMessage, chatMessagesWithoutSystem,
                                cascadeResult.agentResult().toolExchange())
                        : requestMessages;
                revisionCascadeStep = cascadeResult.stepUsed() >= 0 ? cascadeResult.stepUsed() : null;

            } else if (skipCascade) {
                // Agent mode with cascade disabled — use normal agent flow. The streaming
                // bridge is handed ONLY to the tool loop — see runToolLoopIfEnabled.
                var outcome = runToolLoopIfEnabled(chatModel, agentSystemMessage, chatMessagesWithoutSystem, task, memory,
                        effectiveToolApprovals, llmTaskIndex, jsonPolicy, bufferOutput ? null : eventSink, addToOutputExplicitlyFalse,
                        resolvedType, processedParams, retryPolicy, retryListener, currentStep, circuitGate);
                if (outcome != null) {
                    responseContent = outcome.response();
                    toolTrace = outcome.trace();
                    responseMetadata = outcome.responseMetadata();
                    usedToolMode = true;
                    if (outputGuarded) {
                        outputReprompter = toolLoopReprompter(chatModel, agentSystemMessage, chatMessagesWithoutSystem, outcome.toolExchange(), task,
                                memory, jsonPolicy, repromptCircuit);
                    }
                    revisionBase = CascadingModelExecutor.toolTranscript(agentSystemMessage, chatMessagesWithoutSystem,
                            outcome.toolExchange() != null ? outcome.toolExchange() : List.of());
                    revisionToolTranscript = true;
                } else {
                    var chatResult = executePlain(retryPolicy, chatModel, messages, task, jsonPolicy, contextShrinker, retryListener, currentStep,
                            circuitGate, memory.getTurnDeadline());
                    responseContent = chatResult.response();
                    responseMetadata = chatResult.responseMetadata();
                    // Forward the buffered response to the stream so an SSE client is not left
                    // empty.
                    if (eventSink != null && !bufferOutput && responseContent != null && !addToOutputExplicitlyFalse) {
                        eventSink.onToken(responseContent);
                    }
                    if (outputGuarded) {
                        outputReprompter = plainReprompter(chatModel, requestMessages, task, memory, jsonPolicy, repromptCircuit);
                    }
                    revisionBase = requestMessages;
                }

            } else {
                // === Standard (non-cascade) execution path ===
                var outcome = runToolLoopIfEnabled(chatModel, agentSystemMessage, chatMessagesWithoutSystem, task, memory,
                        effectiveToolApprovals, llmTaskIndex, jsonPolicy, bufferOutput ? null : eventSink, addToOutputExplicitlyFalse,
                        resolvedType, processedParams, retryPolicy, retryListener, currentStep, circuitGate);

                if (outputGuarded) {
                    outputReprompter = outcome != null
                            ? toolLoopReprompter(chatModel, agentSystemMessage, chatMessagesWithoutSystem, outcome.toolExchange(), task, memory,
                                    jsonPolicy, repromptCircuit)
                            : plainReprompter(chatModel, requestMessages, task, memory, jsonPolicy, repromptCircuit);
                }
                revisionToolTranscript = outcome != null;
                revisionBase = outcome != null
                        ? CascadingModelExecutor.toolTranscript(agentSystemMessage, chatMessagesWithoutSystem,
                                outcome.toolExchange() != null ? outcome.toolExchange() : List.of())
                        : requestMessages;
                if (outcome != null) {
                    responseContent = outcome.response();
                    toolTrace = outcome.trace();
                    responseMetadata = outcome.responseMetadata();
                    usedToolMode = true;
                } else if (eventSink != null) {
                    // Legacy mode with streaming — try to get a streaming model
                    // R5: a task that may re-ask its model is buffered, never streamed — the
                    // tokens of an attempt that is then retried cannot be taken back. The same
                    // holds for an output guardrail that can change the answer (WS4).
                    var streamingModel = bufferOutput ? null : chatModelRegistry.getOrCreateStreaming(resolvedType, processedParams);
                    if (streamingModel != null) {
                        var streamingResult = streamingLegacyChatExecutor.execute(streamingModel, messages, eventSink, task, jsonPolicy);
                        responseContent = streamingResult.response();
                        responseMetadata.putAll(streamingResult.metadata());
                    } else {
                        // Streaming not supported by this builder (or buffered for R5) — fall back
                        // to sync, emit as single chunk
                        var chatResult = executePlain(retryPolicy, chatModel, messages, task, jsonPolicy, contextShrinker, retryListener,
                                currentStep, circuitGate, memory.getTurnDeadline());
                        responseContent = chatResult.response();
                        responseMetadata = chatResult.responseMetadata();
                        if (!addToOutputExplicitlyFalse && !bufferOutput) {
                            eventSink.onToken(responseContent);
                        }
                    }
                } else {
                    // Standard non-streaming legacy mode
                    var chatResult = executePlain(retryPolicy, chatModel, messages, task, jsonPolicy, contextShrinker, retryListener, currentStep,
                            circuitGate, memory.getTurnDeadline());
                    responseContent = chatResult.response();
                    responseMetadata = chatResult.responseMetadata();
                }
            }
            settleCircuit(circuitTicket, responseContent, convertObject, processedParams, task);

        } catch (LlmSpendGate.SpendRefusedException refused) {
            // Not a failure of the model phase — it never started — so neither onError
            // nor the circuit is involved: the fallback answer is served unconditionally,
            // where the model's answer would have gone.
            fallbackServed = serveSpendRefusal(task, templateDataObjects, currentStep, refused.refusal(), convertObject, memory);
            responseContent = fallbackServed.content();
            responseMetadata = new HashMap<>();
            toolTrace = new ArrayList<>();
            usedToolMode = task.isAgentMode();
            cascadeAuditModel = null;
        } catch (Exception e) {
            settleCircuit(circuitTicket, e);
            if (!cascadeActive) {
                // A cascade counts each step's failure itself (with the step's model).
                countFailure(e, resolveModelNameOf(processedParams, resolvedType));
            }
            if (!fallbackOnError || memory.isCancelled() || LlmFallbackHandler.isControlFlow(e)) {
                throw e;
            }
            markUncommitted(currentStep, dataBefore);
            fallbackServed = fallbackHandler.serve(task, templateDataObjects, currentStep, "onError", e, convertObject, memory.getConversationId(),
                    memory.getAgentId(), null);
            responseContent = fallbackServed.content();
            responseMetadata = new HashMap<>();
            toolTrace = new ArrayList<>();
            usedToolMode = false;
            cascadeAuditModel = null;
        }

        // === Response Validation (Phase D) ===
        // A fallback served by onError is the engine's own text — it is not validated
        // (a refusal-prefix match on the author's apology would otherwise re-trigger).
        // The model's own text (if any) already reached a live stream; a fallback may
        // be
        // appended to the stream only when nothing did, since SSE cannot retract
        // tokens.
        // A task that may re-ask is buffered (R5): nothing has been emitted yet, so the
        // single emission below carries the final answer or the fallback, never both.
        boolean modelTextStreamed = fallbackServed == null && !isNullOrEmpty(responseContent) && !bufferOutput;
        if (fallbackServed == null) {
            try {
                var validated = applyResponseValidation(responseContent, responseMetadata, task, currentStep, templateDataObjects, memory,
                        convertObject, processedParams.get(KEY_RESPONSE_SCHEMA));
                responseContent = validated.content();
                fallbackServed = validated.fallback();
                // === Output guardrails (WS4) === — on the validated answer, before it is
                // stored, parsed or delivered. Not on a fallback: that is the author's text.
                if (fallbackServed == null && outputGuarded && responseContent != null) {
                    // The answer's own cost is not in audit:cost yet (it is recorded below), so
                    // the phase's spend checks start from it.
                    TaskSpend outputSpend = TaskSpend.of(spendGate, memory);
                    outputSpend.add(pricedCost(responseMetadata, task));
                    var screened = guardrailService.runOutput(task.getOutputGuardrails(), responseContent, outputReprompter,
                            guardrailContext(memory, task, requestMessages, retrievedContextText).withSpend(outputSpend),
                            outcome -> recordGuardrailOutcome(memory, currentStep, task, outcome));
                    responseMetadata = foldRepromptUsage(responseMetadata, screened.reprompts());
                    accumulateCost(currentStep, screened.costUsd());
                    responseContent = screened.text();
                    if (screened.isBlocked()) {
                        fallbackServed = guardrailBlock(task, currentStep, screened.text(), convertObject);
                        // Stored like every other fallback: the fallbackField-wrapped content.
                        responseContent = fallbackServed.content();
                    }
                }
            } catch (LifecycleException validationFailure) {
                // A validation policy of "error" — or a failed guardrail reprompt — is a
                // deliberate failure of the model phase: with onError=fallback it is
                // absorbed like any other.
                if (!fallbackOnError || memory.isCancelled() || LlmFallbackHandler.isControlFlow(validationFailure)) {
                    throw validationFailure;
                }
                markUncommitted(currentStep, dataBefore);
                fallbackServed = fallbackHandler.serve(task, templateDataObjects, currentStep, "onError", validationFailure,
                        convertObject, memory.getConversationId(), memory.getAgentId(), null);
                responseContent = fallbackServed.content();
            }
        }

        recordAbnormalFinish(currentStep, responseMetadata);

        if (fallbackServed != null && eventSink != null && !addToOutputExplicitlyFalse && !modelTextStreamed) {
            eventSink.onToken(fallbackServed.text());
        } else if (bufferOutput && fallbackServed == null && eventSink != null && !addToOutputExplicitlyFalse && responseContent != null) {
            eventSink.onToken(responseContent);
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

        if (fallbackServed != null) {
            // The engine's own text, never parsed: it is neither a model output to
            // classify (eddi.llm.output) nor invalid JSON. Under fallbackField it is
            // already the Map the postResponse templates expect.
            templateDataObjects.put(responseObjectName, fallbackServed.object());
        } else if (Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT))) {
            templateDataObjects.put(responseObjectName, convertResponseToObject(responseContent, task.getId(), currentStep,
                    processedParams.get(KEY_RESPONSE_SCHEMA), task.getNonBlankFields(), memory));
        } else {
            templateDataObjects.put(responseObjectName, responseContent);
        }

        // Write audit:* memory keys for the audit ledger (only if auditing is enabled)
        if (memory.getAuditCollector() != null) {
            var compiledPrompt = dataFactory.createData(MemoryKeys.AUDIT_COMPILED_PROMPT,
                    systemMessage + "\n---\n" + (processedParams.get(KEY_PROMPT) != null ? processedParams.get(KEY_PROMPT) : ""));
            currentStep.storeData(compiledPrompt);

            // Not for a fallback: the ledger records what the MODEL said.
            if (responseContent != null && fallbackServed == null) {
                var modelResponse = dataFactory.createData(MemoryKeys.AUDIT_MODEL_RESPONSE, responseContent);
                currentStep.storeData(modelResponse);
            }

            // Under cascade, record the actual winning model (provider/model), not the
            // task-level default — an auditor must be able to reconstruct which model
            // produced the answer (#5).
            // resolveModelName, not params["model"]: most providers take "modelName", so
            // the
            // ledger recorded the provider type ("anthropic") instead of the model id.
            String resolvedModelName = resolveModelNameOf(processedParams, resolvedType);
            String modelName = cascadeAuditModel != null ? cascadeAuditModel : resolvedModelName != null ? resolvedModelName : task.getType();
            var modelNameData = dataFactory.createData(MemoryKeys.AUDIT_MODEL_NAME, modelName);
            currentStep.storeData(modelNameData);

            accumulateAuditEvidence(currentStep, responseMetadata, toolTrace, task);
        } else {
            // The turn's token counts are also the caller's: the OpenAI-compatible /v1
            // adapter reports them as `usage`; and its cost is the tenant's — it is what
            // TurnCostMeter charges to the monthly budget. Both are recorded without the
            // rest of the audit evidence when the ledger is off (eddi.audit.enabled=false).
            accumulateUsageAndCost(currentStep, responseMetadata, task);
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
            // A fallback shows its message, not the JSON it was wrapped in for
            // fallbackField.
            String visibleText = fallbackServed != null ? fallbackServed.text() : responseContent;
            var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), visibleText);
            currentStep.storeData(outputData);
            // Only add to conversation output if there is actual text.
            // Null/blank responses (e.g. from token budget exhaustion or
            // thinking-only turns) should not produce empty message bubbles.
            if (producesRenderableOutput(visibleText)) {
                var outputItem = new TextOutputItem(visibleText, 0);
                if (fallbackServed != null) {
                    outputItem.setFallback(true);
                }
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

        // A fallback is the author's text, not an answer the model could revise.
        // It is still the latest answer, so an earlier task's candidate must not
        // stand in for it.
        if (fallbackServed == null) {
            recordRevisionCandidate(memory, task, llmTaskIndex, revisionBase, revisionToolTranscript, revisionCascadeStep, responseContent,
                    shouldAddToOutput, revisionOutputMark, revisionQuickRepliesMark);
        } else if (memory.getHitlRevisionPolicy() != null) {
            memory.setHitlRevisionCandidate(null);
        }

        // Strategy 2: Update rolling summary if configured.
        // IMPORTANT: Must run AFTER the current response is added to
        // conversationOutputs,
        // so totalSteps correctly includes this turn when computing
        // summarizeThroughStep.
        // Moving this earlier would cause the summary boundary to be off by 1.
        // Not after a spend refusal: the summarizer is a model call too.
        if (summaryConfig != null && summaryConfig.isEnabled() && spendRefusal == null) {
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
                var effectiveSummaryConfig = resolveEffectiveSummaryConfig(summaryConfig, resolvedType,
                        resolveModelNameOf(processedParams, resolvedType));
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
     * What a same-model re-ask may still spend: the turn's time after the reserve,
     * or no bound when the turn has no deadline. {@code minAttemptMs} is checked
     * against it, so a re-ask never starts with the turn nearly out of time.
     */
    static FormatRetryRunner.RemainingBudget turnBudget(TurnDeadline deadline) {
        return deadline != null ? deadline::remainingAfterReserveMs : FormatRetryRunner.RemainingBudget.UNBOUNDED;
    }

    /**
     * One recovery action of this task:
     * {@code eddi.llm.recovery{action,outcome,trigger}} and the single structured
     * INFO line (R11).
     */
    private void recordRecovery(IConversationMemory memory, String action, String outcome, String failureClass, long durationMs) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.recovery", "action", action, "outcome", outcome, "trigger", failureClass).increment();
        }
        LlmRecoveryLog.log(memory != null ? memory.getConversationId() : null, memory != null ? memory.getAgentId() : null, failureClass, action,
                outcome, 1, durationMs);
    }

    /**
     * {@code eddi.llm.failure{class,model}} for a failed single-model call (the
     * cascade counts its own steps). Control flow (a pause, a cancel) and the
     * circuit's own refusal are not failures of the model.
     */
    void countFailure(Exception failure, String model) {
        if (meterRegistry == null || failure instanceof LlmCircuitOpenException || LlmFallbackHandler.isControlFlow(failure)) {
            return;
        }
        meterRegistry.counter("eddi.llm.failure", "class", LlmFailureClassifier.classify(failure).cls().name(), "model",
                model == null || model.isBlank() ? "unknown" : model).increment();
    }

    /**
     * Settles the task's circuit ticket (R8) for a model phase that returned: a
     * reply that is still invalid JSON or off-shape is a counted failure, anything
     * else a success. The reply is looked at directly rather than through
     * {@code responseValidation}, so a model that never produces valid JSON is seen
     * whatever the validation settings are.
     */
    private void settleCircuit(LlmCircuitBreakers.Ticket ticket, String responseContent, boolean convertObject,
                               Map<String, String> processedParams, Task task) {
        if (ticket == LlmCircuitBreakers.Ticket.DISABLED) {
            return;
        }
        var detection = convertObject
                ? formatRetryRunner.classifyJsonReply(responseContent, processedParams.get(KEY_RESPONSE_SCHEMA), task.getNonBlankFields(),
                        task.getId())
                : null;
        if (detection != null) {
            ticket.failure(LlmCircuitBreakers.Failure.INVALID_OUTPUT, detection.trigger().label() + ": " + detection.reason());
        } else {
            ticket.success();
        }
    }

    /**
     * Settles the ticket (R8) for a model phase that threw: a permanent provider
     * failure is counted; a pause, a cancel, a transient error or anything
     * unclassified is not.
     */
    private static void settleCircuit(LlmCircuitBreakers.Ticket ticket, Exception failure) {
        if (ticket == LlmCircuitBreakers.Ticket.DISABLED) {
            return;
        }
        var counted = LlmFallbackHandler.isControlFlow(failure) ? null : LlmFailureClassifier.classify(failure);
        var circuitFailure = counted != null ? LlmCircuitBreakers.Failure.of(counted.cls()) : null;
        if (circuitFailure != null) {
            ticket.failure(circuitFailure, counted.reason());
        } else {
            ticket.release();
        }
    }

    /**
     * One plain chat completion, with the same-model recovery of
     * {@code responseValidation} (R5) when a {@code "retry"} policy applies;
     * exactly {@link LegacyChatExecutor#execute} otherwise.
     */
    private LegacyChatExecutor.ChatResult executePlain(FormatRetryRunner.Policy policy, ChatModel chatModel, List<ChatMessage> messages,
                                                       Task task, JsonResponseFormatPolicy jsonPolicy,
                                                       FormatRetryRunner.ContextShrinker shrinker, FormatRetryRunner.ReaskListener listener,
                                                       IWritableConversationStep currentStep, FormatRetryRunner.ReaskGate gate,
                                                       TurnDeadline deadline)
            throws LifecycleException {
        if (policy == null) {
            return legacyChatExecutor.execute(chatModel, messages, task, jsonPolicy, deadline);
        }
        var outcome = formatRetryRunner.run(policy, messages, (msgs, maxTokens) -> {
            var result = legacyChatExecutor.execute(chatModel, msgs, task, jsonPolicy, maxTokens, deadline);
            return new FormatRetryRunner.Attempt(result.response(), result.responseMetadata());
        }, shrinker, turnBudget(deadline), gate, listener);
        recordRetry(currentStep, task, outcome);
        return new LegacyChatExecutor.ChatResult(outcome.attempt().text(), outcome.attempt().metadata());
    }

    /**
     * Notes a recovery on the step ({@code llm:retry:<taskId>}); a no-op without
     * re-asks.
     */
    private void recordRetry(IWritableConversationStep currentStep, Task task, FormatRetryRunner.Outcome outcome) {
        if (outcome.reasks() == 0 || currentStep == null) {
            return;
        }
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("reasks", outcome.reasks());
        note.put("unresolved", outcome.unresolved() != null ? outcome.unresolved().label() : null);
        currentStep.storeData(dataFactory.createData(KEY_RETRY + (task.getId() != null ? task.getId() : "default"), note));
    }

    /**
     * The prompt again with a halved history window, for the one
     * {@code onContextTooLong} re-ask: half the token budget under
     * {@code maxContextTokens}, otherwise half the step window
     * ({@code conversationHistoryLimit}, or half of what the conversation holds
     * when that is unlimited). The anchored opening steps stay, as always. Null
     * when there is nothing left to halve.
     */
    private List<ChatMessage> rebuildWithHalvedWindow(IConversationMemory memory, Task task, String systemMessage, String prompt, int logSizeLimit,
                                                      Integer maxContextTokens, int anchorFirstSteps, boolean includeFirstAgentMessage,
                                                      String resolvedType, Map<String, String> processedParams, String summaryPrefix,
                                                      int skipSteps) {
        List<ChatMessage> smaller;
        if (maxContextTokens != null && maxContextTokens > 0) {
            int half = maxContextTokens / 2;
            if (half < 1) {
                return null;
            }
            var estimator = tokenCounterFactory.getEstimator(resolvedType, resolveModelNameOf(processedParams, resolvedType));
            smaller = conversationHistoryBuilder.buildTokenAwareMessages(memory, systemMessage, prompt, half, anchorFirstSteps,
                    includeFirstAgentMessage, estimator, summaryPrefix, skipSteps);
        } else {
            if (logSizeLimit == 0) {
                return null; // the prompt carried no history: nothing to halve
            }
            int window = logSizeLimit > 0 ? logSizeLimit : Math.max(0, memory.getConversationOutputs().size() - skipSteps);
            int half = window / 2;
            if (half < 1) {
                return null;
            }
            smaller = conversationHistoryBuilder.buildMessages(memory, systemMessage, prompt, half, includeFirstAgentMessage, summaryPrefix,
                    skipSteps);
        }
        forwardAttachments(smaller, memory, task, resolvedType, processedParams);
        return smaller;
    }

    /**
     * Forward the current step's attachments to the LLM as multimodal content,
     * gated on the resolved (provider, model) capabilities, honoring any per-task
     * multimodal overrides.
     */
    private void forwardAttachments(List<ChatMessage> messages, IConversationMemory memory, Task task, String resolvedType,
                                    Map<String, String> processedParams) {
        if (attachmentForwarder == null) {
            return;
        }
        var mm = task.getMultimodal();
        var taskVision = mm != null ? ModelCapabilityService.Support.parse(mm.getVision()) : ModelCapabilityService.Support.AUTO;
        // Per-task override first, then what an openai-compatible endpoint declares.
        var vision = ModelCapabilityService.visionOverride(taskVision, resolvedType, capabilityParams(resolvedType, processedParams));
        var documents = mm != null ? ModelCapabilityService.Support.parse(mm.getDocuments()) : ModelCapabilityService.Support.AUTO;
        var audio = mm != null ? ModelCapabilityService.Support.parse(mm.getAudio()) : ModelCapabilityService.Support.AUTO;
        attachmentForwarder.forward(messages, memory, resolvedType, resolveModelNameOf(processedParams, resolvedType), vision, documents, audio);
    }

    /**
     * The parameters an {@code openai-compatible} endpoint's capability flags are
     * read from: the rendered task parameters with {@code ${vars:...}} references
     * in the flags resolved, which otherwise happens only inside
     * {@code ChatModelRegistry}. Any other type gets the map back unchanged.
     */
    private Map<String, String> capabilityParams(String resolvedType, Map<String, String> processedParams) {
        return OpenAiCompatibleEndpoint.withResolvedFlags(resolvedType, processedParams,
                globalVariableResolver == null ? null : globalVariableResolver::resolveValue);
    }

    /**
     * The step's data entries by key, by identity, taken before the model phase so
     * that {@link #markUncommitted} can tell what the failed phase wrote.
     */
    private static Map<String, IData<?>> snapshotStepData(IWritableConversationStep step) {
        Map<String, IData<?>> snapshot = new HashMap<>();
        List<IData<?>> elements = step.getAllElements();
        if (elements != null) {
            for (IData<?> element : elements) {
                snapshot.put(element.getKey(), element);
            }
        }
        return snapshot;
    }

    /**
     * Commit Flags for a failed model phase (same rule as strict write discipline):
     * every entry the phase added or overwrote is marked uncommitted, so it stays
     * stored for debugging but out of what later turns show the model.
     */
    private static void markUncommitted(IWritableConversationStep step, Map<String, IData<?>> before) {
        List<IData<?>> elements = step.getAllElements();
        if (elements == null) {
            return;
        }
        for (IData<?> element : elements) {
            if (before.get(element.getKey()) != element) {
                element.setCommitted(false);
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
        accumulateUsageAndCost(currentStep, responseMetadata, task);
        accumulateToolCalls(currentStep, toolTrace, task.getId());
    }

    /**
     * The token-usage and dollar-cost half of {@link #accumulateAuditEvidence} —
     * the part that is recorded whether or not the audit ledger is on, because the
     * {@code /v1} adapter reports the tokens and the tenant cost budget charges the
     * cost.
     */
    private void accumulateUsageAndCost(IWritableConversationStep currentStep, Map<String, Object> responseMetadata, Task task) {
        if (responseMetadata == null) {
            return;
        }
        Map<?, ?> tokenUsage = responseMetadata.get("tokenUsage") instanceof Map<?, ?> tu ? tu : null;
        if (tokenUsage != null) {
            accumulateTokenUsage(currentStep, tokenUsage);
        }
        accumulateCost(currentStep, pricedCost(responseMetadata, task));
    }

    /**
     * The priced cost of a model phase's metadata — its tokens at the task's prices
     * (a cascade's own {@code cascadeCostUsd} instead) plus its tool cost. What
     * {@link #accumulateUsageAndCost} records, and what the output phase's spend
     * checks start from before it is recorded.
     */
    static double pricedCost(Map<String, Object> responseMetadata, Task task) {
        if (responseMetadata == null) {
            return 0.0;
        }
        Map<?, ?> tokenUsage = responseMetadata.get("tokenUsage") instanceof Map<?, ?> tu ? tu : null;
        double llmCostUsd = responseMetadata.containsKey("cascadeCostUsd")
                ? asDouble(responseMetadata.get("cascadeCostUsd"))
                : TokenPricing.cost(task.getInputPricePer1M(), task.getOutputPricePer1M(), tokenUsage);
        return llmCostUsd + asDouble(responseMetadata.get("toolCostUsd"));
    }

    /** {@code policy} with the spend check, or null when there is no policy. */
    private static FormatRetryRunner.Policy withSpend(FormatRetryRunner.Policy policy, FormatRetryRunner.ReaskSpend spend) {
        return policy != null ? policy.withSpend(spend) : null;
    }

    /**
     * Leaves {@link MemoryKeys#LLM_FINISH_REASON} on the step when the model's
     * answer was cut off at its token limit ({@code length}) or filtered by the
     * provider ({@code content_filter}), so a caller can report it — the
     * OpenAI-compatible {@code /v1} adapter turns it into {@code finish_reason}. A
     * normal finish writes nothing, so a later sub-task of the same turn cannot
     * overwrite an earlier truncation with "stop".
     */
    private void recordAbnormalFinish(IWritableConversationStep currentStep, Map<String, Object> responseMetadata) {
        String reason = abnormalFinishReason(responseMetadata);
        if (reason != null) {
            currentStep.storeData(dataFactory.createData(MemoryKeys.LLM_FINISH_REASON, reason));
        }
    }

    /**
     * {@code "length"} or {@code "content_filter"} when the response metadata says
     * the model stopped for that reason — as the executors' {@code warning} or as
     * the provider's raw {@code finishReason} — otherwise {@code null}.
     */
    static String abnormalFinishReason(Map<String, Object> responseMetadata) {
        if (responseMetadata == null) {
            return null;
        }
        Object warning = responseMetadata.get("warning");
        String finishReason = String.valueOf(responseMetadata.get("finishReason"));
        if ("truncated".equals(warning) || "LENGTH".equalsIgnoreCase(finishReason)) {
            return "length";
        }
        if ("content_filter".equals(warning) || "CONTENT_FILTER".equalsIgnoreCase(finishReason)) {
            return "content_filter";
        }
        return null;
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

    /**
     * Serves the task's fallback answer in place of a model call the spend gate
     * refused, and records why under {@code llm:spend_refused:<taskId>}. The
     * fallback flags the step {@code llm:fallback:<taskId>}, so the history builder
     * never shows the model this reply.
     */
    private LlmFallbackHandler.Fallback serveSpendRefusal(Task task, Map<String, Object> templateDataObjects,
                                                          IWritableConversationStep currentStep, LlmSpendGate.Refusal refusal,
                                                          boolean convertObject, IConversationMemory memory) {
        String taskId = task.getId() != null ? task.getId() : "default";
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("reason", refusal.reason());
        record.put("detail", refusal.detail());
        currentStep.storeData(dataFactory.createData(MemoryKeys.LLM_SPEND_REFUSED_PREFIX + taskId, record));
        return fallbackHandler.serve(task, templateDataObjects, currentStep, "spend_refused", null, convertObject, memory.getConversationId(),
                memory.getAgentId(), refusal.reason());
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
     * @return the (possibly modified) response content, and the fallback if a
     *         {@code "fallback"} action substituted one
     */
    private Validated applyResponseValidation(String responseContent, Map<String, Object> responseMetadata,
                                              Task task, IWritableConversationStep currentStep,
                                              Map<String, Object> templateDataObjects, IConversationMemory memory, boolean convertObject,
                                              String processedParamsSchema)
            throws LifecycleException {

        ResponseValidation validation = task.getResponseValidation();
        if (validation == null || !validation.isEnabled()) {
            return new Validated(responseContent, null);
        }

        String warning = responseMetadata != null ? (String) responseMetadata.get("warning") : null;
        boolean streamingTimeout = responseMetadata != null && Boolean.TRUE.equals(responseMetadata.get("streamingTimeout"));

        // 1. Empty response check
        if (isNullOrEmpty(responseContent)) {
            var step = applyValidationAction(validation.getOnEmpty(), "empty_response",
                    "LLM returned empty response", responseContent, task, currentStep, templateDataObjects, memory, convertObject);
            if (step.fallback() != null) {
                return step;
            }
            responseContent = step.content();
        }

        // 2. Truncation check (finishReason=LENGTH)
        if ("truncated".equals(warning)) {
            var step = applyValidationAction(validation.getOnTruncation(), "truncated_response",
                    "LLM response was truncated (finishReason=LENGTH)", responseContent, task, currentStep, templateDataObjects, memory,
                    convertObject);
            if (step.fallback() != null) {
                return step;
            }
            responseContent = step.content();
        }

        // 3. Content filter check
        if ("content_filter".equals(warning)) {
            var step = applyValidationAction(validation.getOnContentFilter(), "content_filter",
                    "LLM response was blocked by content filter", responseContent, task, currentStep, templateDataObjects, memory, convertObject);
            if (step.fallback() != null) {
                return step;
            }
            responseContent = step.content();
        }

        // 4. Streaming timeout check
        if (streamingTimeout) {
            var step = applyValidationAction(validation.getOnStreamingTimeout(), "streaming_timeout",
                    "Streaming response timed out", responseContent, task, currentStep, templateDataObjects, memory, convertObject);
            if (step.fallback() != null) {
                return step;
            }
            responseContent = step.content();
        }

        // 5. Refusal heuristic — configured prefixes, defaulting to the four that were
        // hard-coded here.
        if (!isNullOrEmpty(responseContent) && looksLikeRefusal(responseContent, validation.getRefusalPatterns())) {
            var step = applyValidationAction(validation.getOnRefusal(), "refusal_detected",
                    "LLM response appears to be a refusal", responseContent, task, currentStep, templateDataObjects, memory, convertObject);
            if (step.fallback() != null) {
                return step;
            }
            responseContent = step.content();
        }

        // 6. Invalid JSON (convertToObject only): after the parser's own local repair.
        // "retry" was already acted on by the executors; reaching it here means the
        // re-asks (and every cascade step) are spent, so fallbackAction applies.
        if (convertObject && (responseContent == null || responseContent.isBlank()) && "retry".equalsIgnoreCase(validation.getOnInvalidJson())) {
            // retry was spent on a blank reply (see FormatRetryRunner): out of recoveries
            var step = applyValidationAction(validation.getOnInvalidJson(), "invalid_json", "LLM response is empty", responseContent, task,
                    currentStep, templateDataObjects, memory, convertObject);
            if (step.fallback() != null) {
                return step;
            }
            responseContent = step.content();
        } else if (convertObject && responseContent != null && !responseContent.isBlank()) {
            var parsed = modelOutputParser.parse(responseContent, true, processedParamsSchema, task.getNonBlankFields(), task.getId());
            if (parsed.kind() == ModelOutputParser.Kind.SCHEMA_MISMATCH) {
                var step = applyValidationAction(validation.getOnSchemaMismatch(), "schema_mismatch",
                        "LLM response does not match the response shape (" + parsed.reason() + ")", responseContent, task, currentStep,
                        templateDataObjects, memory, convertObject);
                if (step.fallback() != null) {
                    return step;
                }
                responseContent = step.content();
            } else if (parsed.kind() == ModelOutputParser.Kind.INVALID) {
                var step = applyValidationAction(validation.getOnInvalidJson(), "invalid_json",
                        "LLM response is not valid JSON (" + parsed.reason() + ")", responseContent, task, currentStep, templateDataObjects, memory,
                        convertObject);
                if (step.fallback() != null) {
                    return step;
                }
                responseContent = step.content();
            }
        }

        return new Validated(responseContent, null);
    }

    /**
     * What response validation hands back: the text to carry on with, and the
     * fallback when a {@code "fallback"} action replaced the model's answer (so the
     * caller can skip parsing it and flag the turn).
     */
    private record Validated(String content, LlmFallbackHandler.Fallback fallback) {
    }

    /** Validation types whose {@code "retry"} action the executors act on. */
    private static final Set<String> RETRYABLE_VALIDATIONS = Set.of("empty_response", "truncated_response", "content_filter", "invalid_json",
            "schema_mismatch");

    /**
     * Whether a completion opens with one of the configured refusal prefixes.
     * <p>
     * Blank patterns are dropped rather than matched.
     * {@code "".startsWith(anything)} is true for every response, so one stray
     * empty entry would apply {@code onRefusal} to every completion — and under
     * {@code onRefusal: "error"}, fail every turn.
     * <p>
     * {@code Locale.ROOT} because the bare {@code toLowerCase()} mangles the
     * dotted/dotless I on a Turkish-locale JVM, which would silently stop "I
     * cannot" matching on exactly the deployments least likely to notice.
     *
     * @param responseContent
     *            the model's completion; leading and trailing space is ignored
     * @param patterns
     *            the configured prefixes, or {@code null} to detect nothing
     */
    static boolean looksLikeRefusal(String responseContent, List<String> patterns) {
        if (patterns == null || responseContent == null) {
            return false;
        }
        String lower = responseContent.trim().toLowerCase(Locale.ROOT);
        return patterns.stream().filter(Objects::nonNull).map(String::trim).filter(p -> !p.isEmpty()).map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(lower::startsWith);
    }

    /**
     * Applies a single validation action.
     *
     * @return the (possibly modified) response content, and the fallback if one was
     *         substituted
     */
    private Validated applyValidationAction(String action, String validationType, String message,
                                            String responseContent, Task task, IWritableConversationStep currentStep,
                                            Map<String, Object> templateDataObjects, IConversationMemory memory, boolean convertObject)
            throws LifecycleException {

        if (action == null || "ignore".equalsIgnoreCase(action)) {
            return new Validated(responseContent, null);
        }

        switch (action.toLowerCase()) {
            case "warn" :
                LOGGER.warnf("[ResponseValidation] %s (task=%s): %s", validationType, task.getId(), message);
                var warnData = dataFactory.createData("llm:validation:" + validationType + ":" + task.getId(), message);
                currentStep.storeData(warnData);
                break;

            case "fallback" :
                LOGGER.warnf("[ResponseValidation] %s — substituting fallback (task=%s)", validationType, task.getId());
                var fallbackData = dataFactory.createData("llm:validation:" + validationType + ":" + task.getId(),
                        Map.of("action", "fallback", "original", responseContent != null ? responseContent : ""));
                currentStep.storeData(fallbackData);
                var served = fallbackHandler.serve(task, templateDataObjects, currentStep, "validation", null, convertObject,
                        memory.getConversationId(), memory.getAgentId(), validationType);
                return new Validated(served.content(), served);

            case "error" :
                LOGGER.errorf("[ResponseValidation] %s — throwing error (task=%s): %s", validationType, task.getId(), message);
                throw new LifecycleException("Response validation failed [" + validationType + "]: " + message);

            case "retry" :
                // The re-asks happened (or were skipped) before validation ran; a reply
                // still failing here is out of recoveries. Only the policies that are
                // re-asked can say "retry"; for the others it is a configuration slip.
                if (!RETRYABLE_VALIDATIONS.contains(validationType)) {
                    LOGGER.warnf("[ResponseValidation] 'retry' is not available for %s (task=%s), treating as 'warn'", validationType,
                            task.getId());
                    currentStep.storeData(dataFactory.createData("llm:validation:" + validationType + ":" + task.getId(), message));
                    break;
                }
                String exhausted = task.getResponseValidation() != null ? task.getResponseValidation().getFallbackAction() : null;
                if (exhausted == null || "retry".equalsIgnoreCase(exhausted)) {
                    exhausted = "fallback";
                }
                LOGGER.warnf("[ResponseValidation] %s — retries exhausted, applying fallbackAction '%s' (task=%s)", validationType, exhausted,
                        task.getId());
                return applyValidationAction(exhausted, validationType, message, responseContent, task, currentStep, templateDataObjects, memory,
                        convertObject);

            default :
                LOGGER.warnf("[ResponseValidation] Unknown action '%s' for %s, treating as 'warn'", action, validationType);
                var unknownData = dataFactory.createData("llm:validation:" + validationType + ":" + task.getId(), message);
                currentStep.storeData(unknownData);
                break;
        }

        return new Validated(responseContent, null);
    }

    /**
     * Retrieved context, wrapped in a provenance envelope unless the task turned
     * that off ({@code markRagProvenance: false}). See
     * {@link ToolResultProvenance#markRetrieved}.
     */
    static String markRetrievedContext(Task task, String source, String context) {
        return Boolean.FALSE.equals(task.getMarkRagProvenance()) ? context : ToolResultProvenance.markRetrieved(source, context);
    }

    /**
     * The {@code convertToObject} view of a response, shared by the live and the
     * HITL-resume path: a JSON object becomes a Map, a JSON array a List (after
     * {@link ModelOutputParser}'s fence stripping / extraction), and anything else
     * stays the raw string. It never throws: a model that ran out of tokens
     * mid-object used to fail the whole turn, after it had been paid for.
     * <p>
     * The outcome ({@code valid|repaired|invalid|empty}) is recorded on the step
     * under {@code llm:output:outcome:<taskId>} and counted in
     * {@code eddi.llm.output{outcome}}. The key's prefix keeps it out of client
     * snapshots.
     */
    Object convertResponseToObject(String responseContent, String taskId, IWritableConversationStep currentStep) {
        return convertResponseToObject(responseContent, taskId, currentStep, null, null);
    }

    /**
     * As above, then checks the parsed object against the task's
     * {@code responseSchema} parameter and {@code nonBlankFields}. A violation is
     * outcome {@code schema_mismatch}: the <em>parsed</em> object is still stored
     * (templates that worked on a partially valid object keep working), the
     * EDDI-generated reason goes under {@code llm:output:reason:<taskId>}, and the
     * response-validation policy (not this method) decides what to do about it.
     */
    Object convertResponseToObject(String responseContent, String taskId, IWritableConversationStep currentStep, String responseSchema,
                                   List<String> nonBlankFields) {
        return convertResponseToObject(responseContent, taskId, currentStep, responseSchema, nonBlankFields, null);
    }

    /**
     * As above; {@code memory} names the conversation and agent in the one
     * structured line a local repair (fence stripping, prose extraction) leaves.
     */
    Object convertResponseToObject(String responseContent, String taskId, IWritableConversationStep currentStep, String responseSchema,
                                   List<String> nonBlankFields, IConversationMemory memory) {
        long started = System.nanoTime();
        ModelOutputParser.JsonOutcome outcome = modelOutputParser.parse(responseContent, true, responseSchema, nonBlankFields, taskId);
        String label = outcome.label();
        switch (outcome.kind()) {
            case SCHEMA_MISMATCH -> LOGGER.warnf("convertToObject=true but the response of task '%s' does not match the expected shape (%s)",
                    taskId, outcome.reason());
            case INVALID -> LOGGER.warnf("convertToObject=true but the response of task '%s' is not valid JSON (%s), storing as string", taskId,
                    outcome.reason());
            case EMPTY -> LOGGER.warnf("convertToObject=true but the response of task '%s' is empty, storing as string", taskId);
            default -> {
                if (outcome.repaired()) {
                    recordRecovery(memory, LlmRecoveryLog.REPAIR, "recovered", "invalid_json", (System.nanoTime() - started) / 1_000_000L);
                }
            }
        }
        if (currentStep != null) {
            currentStep.storeData(dataFactory.createData(KEY_OUTPUT_OUTCOME + taskId, label));
            if (outcome.reason() != null) {
                currentStep.storeData(dataFactory.createData(KEY_OUTPUT_REASON + taskId, outcome.reason()));
            }
        }
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.output", "outcome", label).increment();
        }
        return outcome.value();
    }

    /**
     * The cascade step whose tool loop raised this pause, or null to resume on the
     * task's own model: no step recorded (no cascade, or a batch persisted before
     * the field existed), the cascade since disabled, or the index out of range
     * because the config changed during the pause — in which case the base model is
     * the only defensible choice, and the drift is logged.
     */
    static CascadeStep pausedCascadeStep(Task task, PendingToolCallBatch batch) {
        Integer stepIndex = batch.getCascadeStepIndex();
        if (stepIndex == null) {
            return null;
        }
        var cascade = task.getModelCascade();
        var steps = cascade != null && cascade.isEnabled() ? cascade.getSteps() : null;
        if (steps == null || stepIndex < 0 || stepIndex >= steps.size()) {
            LOGGER.warnf("Paused cascade step %d of task '%s' no longer exists; resuming on the task's base model", stepIndex, task.getId());
            return null;
        }
        return steps.get(stepIndex);
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

        final int revisionOutputMark = outputListSize(currentStep, MEMORY_OUTPUT_IDENTIFIER);
        final int revisionQuickRepliesMark = outputListSize(currentStep, MemoryKeys.QUICK_REPLIES_PREFIX);

        // === Rebuild the chat model for THIS task only (normal-path parity) ===
        Map<String, String> processedParams = runTemplateEngineOnParams(task.getParameters(), templateDataObjects, memory);
        var resolvedType = globalVariableResolver.resolveValue(task.getType());
        // M-L1: a pause raised inside a cascade step resumes on THAT step's model.
        // Rebuilding the task's base model continued an escalated step's tool loop
        // on the cheaper model the cascade had already judged not good enough.
        var cascadeStep = pausedCascadeStep(task, batch);
        if (cascadeStep != null) {
            var stepModel = cascadingModelExecutor.resolveStepModel(cascadeStep, task, processedParams, templateDataObjects, memory,
                    "Cascade step");
            resolvedType = stepModel.modelType();
            processedParams = stepModel.params();
        }
        var chatModel = chatModelRegistry.getOrCreate(resolvedType, processedParams);

        // R12: the recovery policies a resumed turn shares with a live one. The model
        // phase below runs under the same onError guard, the final answer is re-asked
        // on the same model when responseValidation says "retry" (a single call over
        // the resumed transcript — no tool ever runs twice), and responseValidation's
        // own actions apply to the answer. NOT applied: the circuit breaker (a resume
        // neither consults nor settles a circuit) and the cascade (the resume pins the
        // step whose tool loop paused). See docs/llm-resilience.md.
        boolean convertObject = Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT));
        FormatRetryRunner.Policy retryPolicy = withSpend(FormatRetryRunner.Policy.from(task.getResponseValidation(), null, convertObject,
                FormatRetryRunner.resolveBaseMaxOutputTokens(processedParams, resolvedType), task.getInputPricePer1M(), task.getOutputPricePer1M(),
                processedParams.get(KEY_RESPONSE_SCHEMA), task.getNonBlankFields(), task.getId()),
                FormatRetryRunner.ReaskSpend.of(spendGate, memory));
        FormatRetryRunner.ReaskListener retryListener = LlmRecoveryLog.reaskListener(memory.getConversationId(), memory.getAgentId(),
                (trigger, attempt) -> {
                    if (memory.getEventSink() != null) {
                        memory.getEventSink().onLlmRetry(trigger.label(), attempt);
                    }
                });
        boolean fallbackOnError = task.getOnError() != null && task.getOnError().isFallback();
        Map<String, IData<?>> dataBefore = fallbackOnError ? snapshotStepData(currentStep) : Map.of();
        LlmFallbackHandler.Fallback fallbackServed = null;

        // === Hand off to the resume loop (Task 9) ===
        // Pass the cluster-wide kill-switch so the continuation's gate resolution
        // matches the live path (executeTask) — a disabled gate stays inert on resume.
        // Same JSON policy the live loop would have applied — a resumed continuation
        // must not silently lose the API-level JSON the paused turn was running with.
        var jsonPolicy = JsonResponseFormatPolicy.of(Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT)), resolvedType,
                task.getJsonResponseFormat(), processedParams.get(KEY_RESPONSE_SCHEMA), capabilityParams(resolvedType, processedParams));

        // Stream the continuation's model rounds too. Replayed transcript rounds
        // never call the model, so the bridge only ever forwards NEW tokens — text
        // streamed before the pause cannot be re-emitted. The resume path has no
        // single-chunk fallback emit, so no suppression bookkeeping is needed here.
        // A task that may re-ask its final answer is buffered, never streamed (R5): the
        // tokens of an attempt that is then retried cannot be taken back.
        // WS4: an output guardrail that can change the answer buffers it the same way.
        boolean outputGuarded = guardrailService != null && !isNullOrEmpty(task.getOutputGuardrails());
        boolean bufferOutput = retryPolicy != null || (outputGuarded && GuardrailService.requiresBuffering(task.getOutputGuardrails()));
        var resumeBridge = bufferOutput
                ? null
                : createToolLoopStreamingBridge(memory.getEventSink(),
                        "false".equalsIgnoreCase(processedParams.get(KEY_ADD_TO_OUTPUT)), resolvedType, processedParams, task);

        // Spend gate: the same preflight as the live path, asked before the approved
        // tools run and before the first resumed model request. A conversation (or a
        // scheduled fire's maxCostPerFire) that was spent while the turn waited for
        // approval gets the fallback answer; the approved calls do not run.
        LlmSpendGate.Refusal spendRefusal = spendGate != null ? spendGate.check(memory) : null;

        AgentOrchestrator.ExecutionResult result = null;
        try {
            if (spendRefusal != null) {
                throw new LlmSpendGate.SpendRefusedException(spendRefusal);
            }
            result = agentOrchestrator.resumeToolLoop(resumeBridge != null ? resumeBridge : chatModel, task, memory, batch, resumeDecision,
                    toolHitlEnabled, jsonPolicy);
            result = reaskResumedAnswer(result, retryPolicy, retryListener, chatModel, task, memory, jsonPolicy, currentStep);
        } catch (LlmSpendGate.SpendRefusedException refused) {
            // Not a failure of the model phase — it never started — so onError is not
            // involved: the fallback answer is served unconditionally (live-path parity).
            fallbackServed = serveSpendRefusal(task, templateDataObjects, currentStep, refused.refusal(), convertObject, memory);
            result = null;
        } catch (ToolApprovalRequiredException rePause) {
            // The continuation hit another gated call and paused again. The loop
            // builds that batch from scratch, and it knows nothing about cascades, so
            // carry the step over: without it the SECOND resume of an escalated step
            // would run on the base model again (M-L1, multi-pause path).
            if (rePause.getBatch() != null && rePause.getBatch().getCascadeStepIndex() == null) {
                rePause.getBatch().setCascadeStepIndex(batch.getCascadeStepIndex());
            }
            throw rePause;
        } catch (Exception failure) {
            // onError: fallback (R7) on the resumed model phase. The approved tools
            // already ran exactly once (the journal), so the failure is of the final
            // answer; a pause, a cancel or a refused approval is control flow and
            // propagates as before.
            countFailure(failure, resolveModelNameOf(processedParams, resolvedType));
            if (!fallbackOnError || memory.isCancelled() || LlmFallbackHandler.isControlFlow(failure)) {
                throw failure;
            }
            markUncommitted(currentStep, dataBefore);
            fallbackServed = fallbackHandler.serve(task, templateDataObjects, currentStep, "onError", failure, convertObject,
                    memory.getConversationId(), memory.getAgentId(), null);
            result = null;
        }

        String responseContent = fallbackServed != null ? fallbackServed.content() : result != null ? result.response() : null;
        List<Map<String, Object>> toolTrace = result != null && result.trace() != null ? result.trace() : new ArrayList<>();
        Map<String, Object> responseMetadata = result != null && result.responseMetadata() != null
                ? new HashMap<>(result.responseMetadata())
                : new HashMap<>();

        // responseValidation's actions (R6) apply to the resumed answer as to a live
        // one; the engine's own fallback text is not validated.
        boolean modelTextStreamed = fallbackServed == null && !isNullOrEmpty(responseContent) && !bufferOutput;
        if (fallbackServed == null) {
            try {
                var validated = applyResponseValidation(responseContent, responseMetadata, task, currentStep, templateDataObjects, memory,
                        convertObject, processedParams.get(KEY_RESPONSE_SCHEMA));
                responseContent = validated.content();
                fallbackServed = validated.fallback();
                // Output guardrails (WS4) apply to a resumed answer as to a live one. A
                // reprompt is one call over the resumed transcript — no tool runs twice.
                if (fallbackServed == null && outputGuarded && responseContent != null) {
                    Reprompter reprompter = result != null && !result.resumeTranscript().isEmpty()
                            ? transcriptReprompter(chatModel, result.resumeTranscript(), task, memory, jsonPolicy, RepromptCircuit.NONE)
                            : null;
                    TaskSpend outputSpend = TaskSpend.of(spendGate, memory);
                    outputSpend.add(pricedCost(responseMetadata, task));
                    var screened = guardrailService.runOutput(task.getOutputGuardrails(), responseContent, reprompter,
                            guardrailContext(memory, task, result != null ? result.resumeTranscript() : List.of()).withSpend(outputSpend),
                            outcome -> recordGuardrailOutcome(memory, currentStep, task, outcome));
                    responseMetadata = foldRepromptUsage(responseMetadata, screened.reprompts());
                    accumulateCost(currentStep, screened.costUsd());
                    responseContent = screened.text();
                    if (screened.isBlocked()) {
                        fallbackServed = guardrailBlock(task, currentStep, screened.text(), convertObject);
                        // Stored like every other fallback: the fallbackField-wrapped content.
                        responseContent = fallbackServed.content();
                    }
                }
            } catch (LifecycleException validationFailure) {
                if (!fallbackOnError || memory.isCancelled() || LlmFallbackHandler.isControlFlow(validationFailure)) {
                    throw validationFailure;
                }
                markUncommitted(currentStep, dataBefore);
                fallbackServed = fallbackHandler.serve(task, templateDataObjects, currentStep, "onError", validationFailure, convertObject,
                        memory.getConversationId(), memory.getAgentId(), null);
                responseContent = fallbackServed.content();
            }
        }
        boolean outputSuppressed = "false".equalsIgnoreCase(processedParams.get(KEY_ADD_TO_OUTPUT));
        var resumeSink = memory.getEventSink();
        if (fallbackServed != null && resumeSink != null && !outputSuppressed && !modelTextStreamed) {
            resumeSink.onToken(fallbackServed.text());
        } else if (bufferOutput && fallbackServed == null && resumeSink != null && !outputSuppressed && responseContent != null) {
            resumeSink.onToken(responseContent);
        }

        // === Store the result EXACTLY like the normal path (executeTask) ===
        // Surface metadata the same way executeTask does. The continuation's
        // tokenUsage covers only the post-resume model calls; the pre-pause segment
        // was recorded on this step's audit totals when the pause passed through
        // execute (recordPrePauseSpend), so it is not added again here.
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

        if (fallbackServed != null) {
            // The engine's own text, never parsed (as on the live path).
            templateDataObjects.put(responseObjectName, fallbackServed.object());
        } else if (convertObject) {
            templateDataObjects.put(responseObjectName, convertResponseToObject(responseContent, task.getId(), currentStep,
                    processedParams.get(KEY_RESPONSE_SCHEMA), task.getNonBlankFields(), memory));
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

            // Not for a fallback: the ledger records what the MODEL said.
            if (responseContent != null && fallbackServed == null) {
                var modelResponse = dataFactory.createData(MemoryKeys.AUDIT_MODEL_RESPONSE, responseContent);
                currentStep.storeData(modelResponse);
            }
            String resolvedModelName = resolveModelNameOf(processedParams, resolvedType);
            String modelName = resolvedModelName != null ? resolvedModelName : task.getType();
            var modelNameData = dataFactory.createData(MemoryKeys.AUDIT_MODEL_NAME, modelName);
            currentStep.storeData(modelNameData);

            // Adds the post-resume spend to the step totals, which already hold the
            // pre-pause segment (see the comment above).
            accumulateAuditEvidence(currentStep, responseMetadata, toolTrace, task);
        } else {
            // The turn's token counts are also the caller's: the OpenAI-compatible /v1
            // adapter reports them as `usage`; and its cost is the tenant's — it is what
            // TurnCostMeter charges to the monthly budget. Both are recorded without the
            // rest of the audit evidence when the ledger is off (eddi.audit.enabled=false).
            accumulateUsageAndCost(currentStep, responseMetadata, task);
        }

        // Tool trace (mirror executeTask)
        if (!toolTrace.isEmpty()) {
            var traceData = dataFactory.createData(LANGCHAIN_TRACE_PREFIX + task.getType() + ":" + task.getId(), toolTrace);
            currentStep.storeData(traceData);
        }

        // Output add. On resume the task used tool mode (that is why it paused), so
        // add the final response unless addToOutput was explicitly false.
        boolean addToOutputExplicitlyFalse = outputSuppressed;
        if (!addToOutputExplicitlyFalse) {
            // A fallback shows its message, not the JSON it was wrapped in for
            // fallbackField.
            String visibleText = fallbackServed != null ? fallbackServed.text() : responseContent;
            var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), visibleText);
            currentStep.storeData(outputData);
            var outputItem = new TextOutputItem(visibleText, 0);
            if (fallbackServed != null) {
                outputItem.setFallback(true);
            }
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
        }

        // postResponse DOES run on resume — it reacts to the final response, which
        // only exists now that the tool loop completed.
        prePostUtils.runPostResponse(memory, task.getPostResponse(), templateDataObjects, 200, false);

        // A rule pause later in this turn may have a human revise this answer too.
        if (fallbackServed == null && result != null) {
            recordRevisionCandidate(memory, task, index, result.resumeTranscript(), true, batch.getCascadeStepIndex(), responseContent,
                    !addToOutputExplicitlyFalse, revisionOutputMark, revisionQuickRepliesMark);
        } else if (memory.getHitlRevisionPolicy() != null) {
            memory.setHitlRevisionCandidate(null);
        }

        // Batch consumed — clear the transient tool-pause state so the next turn does
        // not re-detect resume mode.
        clearToolPauseState(memory);
    }

    /**
     * The same-model re-ask (R5) of a resumed turn's final answer: one model call
     * over the resumed transcript (system, history, every tool exchange up to the
     * answer, corrective message), through
     * {@link IAgentOrchestrator#reaskFinalAnswer} — it ignores any tool request in
     * the reply, so no tool runs a second time. Returns {@code result} untouched
     * when the task does not retry, the resume did not record a transcript, or the
     * answer is fine.
     */
    private AgentOrchestrator.ExecutionResult reaskResumedAnswer(AgentOrchestrator.ExecutionResult result, FormatRetryRunner.Policy retryPolicy,
                                                                 FormatRetryRunner.ReaskListener retryListener, ChatModel chatModel,
                                                                 Task task, IConversationMemory memory, JsonResponseFormatPolicy jsonPolicy,
                                                                 IWritableConversationStep currentStep)
            throws LifecycleException {
        if (result == null || retryPolicy == null || result.resumeTranscript().isEmpty()) {
            return result;
        }
        var recovered = formatRetryRunner.resolve(retryPolicy, new FormatRetryRunner.Attempt(result.response(), result.responseMetadata()),
                result.resumeTranscript(), (msgs, maxTokens) -> {
                    var reply = agentOrchestrator.reaskFinalAnswer(chatModel, msgs, maxTokens, task, memory, jsonPolicy);
                    if (reply == null) {
                        throw new LifecycleException("The tool loop cannot re-ask its final answer");
                    }
                    return new FormatRetryRunner.Attempt(reply.response() != null ? reply.response() : "", reply.responseMetadata());
                }, turnBudget(memory.getTurnDeadline()), FormatRetryRunner.ReaskGate.ALWAYS, retryListener);
        recordRetry(currentStep, task, recovered);
        if (recovered.reasks() == 0) {
            return result;
        }
        Map<String, Object> metadata = new HashMap<>(result.responseMetadata());
        if (recovered.attempt().metadata() != null && recovered.attempt().metadata().get("tokenUsage") != null) {
            metadata.put("tokenUsage", recovered.attempt().metadata().get("tokenUsage"));
        }
        return new AgentOrchestrator.ExecutionResult(recovered.attempt().text(), result.trace(), metadata, result.toolExchange(),
                result.resumeTranscript());
    }

    // === onRejection: REVISE — a human's note revises the answer (E2) ===

    /** Length of a list-valued conversation-output entry of the step, 0 if none. */
    private static int outputListSize(IWritableConversationStep step, String key) {
        var output = step.getConversationOutput();
        return output != null && output.get(key) instanceof List<?> list ? list.size() : 0;
    }

    /**
     * Leaves this answer on memory as the one a later RULE pause could have a human
     * revise. Only while the agent's policy is {@code REVISE}: otherwise nothing is
     * serialized and nothing changes. The request is frozen like a tool pause's
     * transcript, under {@code eddi.hitl.revision.transcript-max-bytes}; over it,
     * the candidate carries no transcript and a rejection skips.
     */
    private void recordRevisionCandidate(IConversationMemory memory, Task task, int llmTaskIndex, List<ChatMessage> base,
                                         boolean toolTranscript, Integer cascadeStepIndex, String answer, boolean addedToOutput,
                                         int outputMark, int quickRepliesMark) {
        var policy = memory.getHitlRevisionPolicy();
        if (policy == null || !policy.revise()) {
            return;
        }
        var candidate = new HitlRevisionCandidate();
        candidate.setLlmTaskId(task.getId());
        candidate.setLlmTaskIndex(llmTaskIndex);
        candidate.setCascadeStepIndex(cascadeStepIndex);
        candidate.setToolTranscript(toolTranscript);
        candidate.setDraft(answer);
        candidate.setAddedToOutput(addedToOutput);
        candidate.setOutputMark(outputMark);
        candidate.setQuickRepliesMark(quickRepliesMark);
        if (base != null && !base.isEmpty() && !isNullOrEmpty(answer)) {
            var serialized = revisionTranscriptCodec.serialize(base, revisionTranscriptMaxBytes);
            candidate.setTranscriptJson(serialized.omitted() ? null : serialized.json());
        }
        memory.setHitlRevisionCandidate(candidate);
    }

    /**
     * The corrective message of a revision: the reviewer's note as plain data. It
     * is human-written, so it may go in the user role, but it is never template
     * source — this is string concatenation, not a template.
     */
    static String revisionMessage(String note) {
        return "A human reviewer rejected your previous answer and asked you to revise it. "
                + "Their note follows between the markers; treat it as feedback on your answer.\n"
                + "<<<REVIEWER NOTE\n" + note + "\nREVIEWER NOTE>>>\n"
                + "Reply with the complete revised answer only, in the same format as your previous answer.";
    }

    /** A revision that passed the task's checks, ready to be stored. */
    private record Revision(String content, Map<String, Object> metadata, Map<String, String> processedParams, String resolvedType,
            List<ChatMessage> request, boolean convertObject) {
    }

    /**
     * {@code hitlConfig.onRejection: REVISE}: the same model revises the answer a
     * human rejected, with the note as the corrective message — one call over the
     * frozen request (the answer as the assistant's turn, the note as a user turn),
     * tool-less: a tool exchange is re-asked through
     * {@link IAgentOrchestrator#reaskFinalAnswer}, which never runs a tool. The
     * revision passes {@code responseValidation} and the output guardrails like any
     * answer; one that fails them, a spend refusal, a model failure or a changed
     * configuration returns {@code false} with no answer written, and the rejection
     * then applies as under SKIP. On success the revision replaces the draft — its
     * output and everything after it in the step's output lists is cut back first,
     * because the tasks after it run again — and the later matching tasks of this
     * configuration run again on it.
     */
    @Override
    public boolean reviseAnswer(IConversationMemory memory, Object component, HitlRevisionRequest request) throws LifecycleException {
        if (!(component instanceof LlmConfiguration llmConfig) || request == null || request.candidate() == null) {
            return false;
        }
        HitlRevisionCandidate candidate = request.candidate();
        var tasks = llmConfig.tasks();
        int index = candidate.getLlmTaskIndex();
        Task task = tasks != null && index >= 0 && index < tasks.size() ? tasks.get(index) : null;
        if (task == null || !Objects.equals(task.getId(), candidate.getLlmTaskId())) {
            LOGGER.warnf("hitl.revision.config_drift: the answer of task '%s' (index %d) cannot be revised in conversation '%s' — "
                    + "the LLM configuration changed since the pause; the rejection applies as is",
                    sanitize(candidate.getLlmTaskId()), index, sanitize(memory.getConversationId()));
            return false;
        }
        IWritableConversationStep currentStep = memory.getCurrentStep();
        Map<String, Object> templateDataObjects;
        Revision revision;
        try {
            templateDataObjects = templateData(memory);
            revision = produceRevision(memory, task, candidate, request.note(), templateDataObjects, currentStep);
        } catch (LifecycleException | RuntimeException | ChatModelRegistry.UnsupportedLlmTaskException e) {
            LOGGER.warnf("hitl.revision.failed: revising the answer of task '%s' in conversation '%s' failed (%s) — the rejection applies as is",
                    sanitize(task.getId()), sanitize(memory.getConversationId()), sanitize(e.getClass().getSimpleName()));
            return false;
        }
        if (revision == null) {
            return false;
        }
        try {
            storeRevision(memory, task, index, candidate, revision, templateDataObjects, currentStep);
        } catch (IOException | ITemplatingEngine.TemplateEngineException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }

        // The later tasks of this configuration ran on the draft before the pause;
        // their output was cut back with it, so they run again on the revision.
        IData<List<String>> latestActions = currentStep.getLatestData(ACTIONS);
        List<String> actions = latestActions != null && latestActions.getResult() != null ? latestActions.getResult() : List.of();
        try {
            for (int taskIndex = index + 1; taskIndex < tasks.size(); taskIndex++) {
                var later = tasks.get(taskIndex);
                if (later.getActions().contains(MATCH_ALL_OPERATOR) || later.getActions().stream().anyMatch(actions::contains)) {
                    executeTask(memory, later, currentStep, templateDataObjects, taskIndex);
                }
            }
        } catch (ITemplatingEngine.TemplateEngineException | ChatModelRegistry.UnsupportedLlmTaskException | IOException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
        return true;
    }

    /** The revised answer, or null when it cannot be produced or fails a check. */
    private Revision produceRevision(IConversationMemory memory, Task task, HitlRevisionCandidate candidate, String note,
                                     Map<String, Object> templateDataObjects, IWritableConversationStep currentStep)
            throws LifecycleException, ChatModelRegistry.UnsupportedLlmTaskException {
        if (candidate.getTranscriptJson() == null || isNullOrEmpty(candidate.getDraft()) || isNullOrEmpty(note)) {
            return null;
        }
        List<ChatMessage> base;
        try {
            base = revisionTranscriptCodec.deserialize(candidate.getTranscriptJson());
        } catch (ChatTranscriptCodec.TranscriptCodecException e) {
            LOGGER.warnf("hitl.revision.failed: the kept request of task '%s' cannot be read back — the rejection applies as is",
                    sanitize(task.getId()));
            return null;
        }
        // No unmetered attempt: the revision is a model call like any other.
        if (spendGate != null && spendGate.check(memory) != null) {
            LOGGER.infof("hitl.revision: not revising task '%s' in conversation '%s' — a spend limit is reached",
                    sanitize(task.getId()), sanitize(memory.getConversationId()));
            return null;
        }
        Map<String, String> processedParams = runTemplateEngineOnParams(task.getParameters(), templateDataObjects, memory);
        String resolvedType = globalVariableResolver.resolveValue(task.getType());
        // The answer came from a cascade step: the revision goes to that step's model.
        Integer stepIndex = candidate.getCascadeStepIndex();
        var cascade = task.getModelCascade();
        if (stepIndex != null && cascade != null && cascade.isEnabled() && cascade.getSteps() != null && stepIndex >= 0
                && stepIndex < cascade.getSteps().size()) {
            var stepModel = cascadingModelExecutor.resolveStepModel(cascade.getSteps().get(stepIndex), task, processedParams, templateDataObjects,
                    memory, "Cascade step");
            resolvedType = stepModel.modelType();
            processedParams = stepModel.params();
        }
        final var chatModel = chatModelRegistry.getOrCreate(resolvedType, processedParams);
        boolean convertObject = Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT));
        var jsonPolicy = JsonResponseFormatPolicy.of(convertObject, resolvedType, task.getJsonResponseFormat(),
                processedParams.get(KEY_RESPONSE_SCHEMA), capabilityParams(resolvedType, processedParams));

        List<ChatMessage> request = new ArrayList<>(base);
        request.add(AiMessage.from(candidate.getDraft()));
        request.add(UserMessage.from(revisionMessage(note)));

        FormatRetryRunner.Asker asker = candidate.isToolTranscript()
                ? (msgs, maxTokens) -> {
                    var reply = agentOrchestrator.reaskFinalAnswer(chatModel, msgs, maxTokens, task, memory, jsonPolicy);
                    if (reply == null) {
                        throw new LifecycleException("The tool loop cannot re-ask its final answer");
                    }
                    return new FormatRetryRunner.Attempt(reply.response() != null ? reply.response() : "", reply.responseMetadata());
                }
                : (msgs, maxTokens) -> {
                    var reply = legacyChatExecutor.execute(chatModel, msgs, task, jsonPolicy, maxTokens, memory.getTurnDeadline());
                    return new FormatRetryRunner.Attempt(reply.response(), reply.responseMetadata());
                };
        var attempt = asker.ask(request, null);
        Map<String, Object> metadata = attempt.metadata() != null ? new HashMap<>(attempt.metadata()) : new HashMap<>();
        String content = attempt.text();
        if (content == null || content.isBlank()) {
            LOGGER.warnf("hitl.revision.failed: the model returned no revision for task '%s' — the rejection applies as is", sanitize(task.getId()));
            accumulateRevisionSpend(currentStep, metadata, task);
            return null;
        }

        // The revision passes the same checks as any answer: a human's note must not
        // steer the model around a validation or a guardrail.
        var validated = applyResponseValidation(content, metadata, task, currentStep, templateDataObjects, memory, convertObject,
                processedParams.get(KEY_RESPONSE_SCHEMA));
        if (validated.fallback() != null) {
            accumulateRevisionSpend(currentStep, metadata, task);
            return null;
        }
        content = validated.content();
        if (guardrailService != null && !isNullOrEmpty(task.getOutputGuardrails()) && content != null) {
            Reprompter reprompter = reprompter(request, asker, memory, task, RepromptCircuit.NONE);
            var screened = guardrailService.runOutput(task.getOutputGuardrails(), content, reprompter, guardrailContext(memory, task, request),
                    outcome -> recordGuardrailOutcome(memory, currentStep, task, outcome));
            metadata = foldRepromptUsage(metadata, screened.reprompts());
            accumulateCost(currentStep, screened.costUsd());
            if (screened.isBlocked()) {
                accumulateRevisionSpend(currentStep, metadata, task);
                return null;
            }
            content = screened.text();
        }
        return new Revision(content, metadata, processedParams, resolvedType, request, convertObject);
    }

    /**
     * A revision that is not stored was still a model call: its tokens and cost
     * belong to the step like any other.
     */
    private void accumulateRevisionSpend(IWritableConversationStep currentStep, Map<String, Object> metadata, Task task) {
        accumulateUsageAndCost(currentStep, metadata, task);
    }

    /**
     * Stores the revision where the draft was, as the live path stores an answer,
     * and leaves it on memory as the next candidate — so a rule that pauses again
     * on it can have it revised again, against the whole exchange so far.
     */
    private void storeRevision(IConversationMemory memory, Task task, int llmTaskIndex, HitlRevisionCandidate candidate, Revision revision,
                               Map<String, Object> templateDataObjects, IWritableConversationStep currentStep)
            throws IOException, ITemplatingEngine.TemplateEngineException {
        truncateOutputList(currentStep, MEMORY_OUTPUT_IDENTIFIER, candidate.getOutputMark());
        truncateOutputList(currentStep, MemoryKeys.QUICK_REPLIES_PREFIX, candidate.getQuickRepliesMark());

        String content = revision.content();
        Map<String, Object> metadata = revision.metadata();
        var responseMetadataObjectName = task.getResponseMetadataObjectName();
        if (!isNullOrEmpty(responseMetadataObjectName)) {
            templateDataObjects.put(responseMetadataObjectName, metadata);
            prePostUtils.createMemoryEntry(currentStep, metadata, responseMetadataObjectName, KEY_LANGCHAIN);
        }
        var responseObjectName = isNullOrEmpty(task.getResponseObjectName()) ? task.getId() : task.getResponseObjectName();
        currentStep.storeData(dataFactory.createData(KEY_LANGCHAIN + ":" + task.getType() + ":" + task.getId(), content));
        if (revision.convertObject()) {
            templateDataObjects.put(responseObjectName, convertResponseToObject(content, task.getId(), currentStep,
                    revision.processedParams().get(KEY_RESPONSE_SCHEMA), task.getNonBlankFields(), memory));
        } else {
            templateDataObjects.put(responseObjectName, content);
        }

        if (memory.getAuditCollector() != null) {
            var processedParams = revision.processedParams();
            currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_COMPILED_PROMPT,
                    processedParams.getOrDefault(KEY_SYSTEM_MESSAGE, "") + "\n---\n" + processedParams.getOrDefault(KEY_PROMPT, "")));
            currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_MODEL_RESPONSE, content));
            String modelName = resolveModelNameOf(processedParams, revision.resolvedType());
            currentStep.storeData(dataFactory.createData(MemoryKeys.AUDIT_MODEL_NAME, modelName != null ? modelName : task.getType()));
            accumulateAuditEvidence(currentStep, metadata, List.of(), task);
        } else {
            accumulateUsageAndCost(currentStep, metadata, task);
        }

        if (candidate.isAddedToOutput() && producesRenderableOutput(content)) {
            currentStep.storeData(dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), content));
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(new TextOutputItem(content, 0)));
            if (memory.getEventSink() != null) {
                memory.getEventSink().onToken(content);
            }
        }

        prePostUtils.runPostResponse(memory, task.getPostResponse(), templateDataObjects, 200, false);

        recordRevisionCandidate(memory, task, llmTaskIndex, revision.request(), candidate.isToolTranscript(), candidate.getCascadeStepIndex(),
                content, candidate.isAddedToOutput(), candidate.getOutputMark(), candidate.getQuickRepliesMark());
    }

    /**
     * Cuts a list-valued conversation-output entry back to {@code mark} entries:
     * what the draft and the tasks after it wrote, which run again for the
     * revision. A mark of -1 (unknown) or past the end leaves the list as is.
     */
    private static void truncateOutputList(IWritableConversationStep step, String key, int mark) {
        var output = step.getConversationOutput();
        if (mark < 0 || output == null || !(output.get(key) instanceof List<?> list) || list.size() <= mark) {
            return;
        }
        list.subList(mark, list.size()).clear();
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
    public static final Set<String> TEMPLATE_SKIP_PARAMS = Set.of("apiKey", "signingSecret", "appPassword", "botToken");

    /**
     * A configuration reference MENTIONED in an LLM parameter —
     * {@code {vault:key-name}}, {@code {vars:key}}, {@code {connection:name}} or
     * {@code {caller:token}}, with or without the leading {@code $} (which is plain
     * text to Qute either way).
     * <p>
     * All four namespaces are resolved AFTER templating, by
     * {@code ChatModelRegistry} and {@code SecretResolver}, and none of them has a
     * Qute namespace resolver — so to Qute every one of them is an unresolvable
     * namespaced expression, not just {@code vault}.
     */
    public static final Pattern CONFIG_REF_MENTION = Pattern.compile("\\{(?:vault|eddivault|vars|connection|caller):[^}]*\\}");

    /**
     * Substrings that make a value WORTH running {@link #CONFIG_REF_MENTION}
     * against — the cheap pre-check, so the common no-reference value never pays
     * for a regex.
     * <p>
     * This is a superset filter, not the namespace list: it must match everything
     * the pattern can match, and may match more. The legacy {@code eddivault:}
     * namespace is deliberately absent because {@code "eddivault:"} CONTAINS
     * {@code "vault:"}, so it is already covered — dropping {@code "vault:"} from
     * this list would silently stop escaping the legacy prefix too. Any namespace
     * added to the pattern must have a substring of it present here;
     * {@code LlmTaskVaultMentionTest} fails if one does not.
     */
    private static final List<String> CONFIG_REF_NAMESPACES = List.of("vault:", "vars:", "connection:", "caller:");

    /**
     * Wraps configuration-reference mentions ({@code vault}, {@code vars},
     * {@code connection}, {@code caller}) in Qute raw sections, so a parameter may
     * CARRY or TALK ABOUT one without crashing templating.
     * <p>
     * Two shapes hit this. A prompt that documents the syntax, and a parameter
     * whose value IS a reference — {@code "modelName": "${vars:gemini-model}"} is
     * resolved by {@code ChatModelRegistry} after templating, but Qute sees it
     * first. Before this escaped more than {@code vault}, such an agent logged "No
     * namespace resolver found for [vars]" on every single turn (observed live),
     * and the parameter fell back to its raw value — which happens to be right for
     * a value that is ONLY a reference, and silently wrong for one that also
     * contains a real expression, since the whole render is abandoned.
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
     * Escaping HERE, for LLM parameters only, threads that needle. The prompts
     * ({@code systemMessage}, {@code prompt}) go to the model and never through
     * vault resolution, so a literal {@code ${vault:key-name}} there is inert
     * documentation. Every OTHER parameter does reach vault resolution —
     * {@code ChatModelRegistry} resolves {@code ${vars:…}} and {@code ${vault:…}}
     * in the builder parameters after templating — which is why
     * {@link #runTemplateEngineOnParams} refuses a reference in those that the
     * configuration did not write. Httpcall templating does not pass through this
     * method and keeps failing loudly, exactly as that security decision requires.
     * <p>
     * Known limit: a mention already inside a {@code {|raw|}} section would be
     * double-wrapped and render its markers. Prompts do not write Qute raw
     * sections; accepting that beats parsing Qute here.
     */
    static String escapeConfigReferenceMentions(String value) {
        if (value == null || CONFIG_REF_NAMESPACES.stream().noneMatch(value::contains)) {
            return value;
        }
        return CONFIG_REF_MENTION.matcher(value).replaceAll(match -> "{|" + match.group() + "|}");
    }

    /**
     * Parameters that go to the model as text and are never resolved against the
     * vault, so conversation data in them may carry anything — including the
     * characters of a vault reference — without releasing a secret.
     */
    static final Set<String> PROMPT_PARAMS = Set.of(KEY_SYSTEM_MESSAGE, KEY_PROMPT);

    /**
     * Render the task parameters, refusing a credential reference that came from
     * conversation data.
     * <p>
     * Every parameter except the prompts is resolved by {@code ChatModelRegistry}
     * after templating: global variables first, then {@code ${vault:…}}. A
     * parameter such as {@code "modelName": "{context.model}"} or {@code "baseUrl":
     * "{properties.endpoint}"} therefore used to resolve a
     * {@code ${vault:another-agents-key}} a user typed into it — with no grant
     * check — and hand the plaintext to the provider client, whose errors routinely
     * echo the value back. A reference is resolved only where the configuration
     * wrote it (the same rule {@link ConfigReferenceGuard} applies to httpcalls),
     * or where a named property holds this conversation's own auto-vaulted secret.
     *
     * @throws LifecycleException
     *             when a rendered builder parameter carries a vault, connection,
     *             caller or global-variable reference its configured template does
     *             not
     */
    private HashMap<String, String> runTemplateEngineOnParams(Map<String, String> parameters, Map<String, Object> templateDataObjects,
                                                              IConversationMemory memory)
            throws LifecycleException {

        var processedParams = new HashMap<>(parameters);
        processedParams.forEach((key, value) -> {
            try {
                if (!isNullOrEmpty(value) && !TEMPLATE_SKIP_PARAMS.contains(key)) {
                    processedParams.put(key, templatingEngine.processTemplate(escapeConfigReferenceMentions(value), templateDataObjects));
                }
            } catch (ITemplatingEngine.TemplateEngineException e) {
                LOGGER.errorf(e, "Template processing failed for LLM parameter '%s': %s", key, e.getLocalizedMessage());
            }
        });
        Map<String, Property> conversationProperties = memory != null && memory.getConversationProperties() != null
                ? memory.getConversationProperties()
                : Map.of();
        guardRenderedParameters(parameters, processedParams, templateDataObjects, conversationProperties);
        return processedParams;
    }

    /**
     * {@link ConfigReferenceGuard#requireConfiguredParameters} for the task
     * parameters: every one of them reaches vault resolution except
     * {@link #PROMPT_PARAMS}.
     *
     * @throws LifecycleException
     *             naming the first parameter that carries a reference its template
     *             did not write
     */
    static void guardRenderedParameters(Map<String, String> configured, Map<String, String> rendered, Map<String, Object> templateData,
                                        Map<String, Property> conversationProperties)
            throws LifecycleException {
        try {
            ConfigReferenceGuard.requireConfiguredParameters(configured, rendered, PROMPT_PARAMS, "LLM", templateData, conversationProperties);
        } catch (IllegalArgumentException e) {
            throw new LifecycleException(e.getMessage(), e);
        }
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
        // The parent's model is only meaningful to the parent's provider: a summary
        // config naming another vendor with no model of its own would otherwise send,
        // say, an xAI parent's grok-4.7 to Anthropic. Left unset, the summary
        // provider's builder applies its own default.
        boolean sameProvider = providerMissing
                || (!isNullOrEmpty(parentProvider) && configured.getLlmProvider().trim().equalsIgnoreCase(parentProvider.trim()));
        effective.setLlmModel(modelMissing ? (sameProvider ? parentModel : null) : configured.getLlmModel());
        effective.setMaxSummaryTokens(configured.getMaxSummaryTokens());
        effective.setExcludePropertiesFromSummary(configured.isExcludePropertiesFromSummary());
        effective.setRecentWindowSteps(configured.getRecentWindowSteps());
        effective.setMaxRecallTurns(configured.getMaxRecallTurns());
        effective.setMaxTurnsPerUpdate(configured.getMaxTurnsPerUpdate());
        effective.setMaxCharsPerUpdate(configured.getMaxCharsPerUpdate());
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
     * Parameter keys that belong to the provider that issued them: credentials, the
     * endpoint coordinates that address that provider's account, and the model keys
     * ({@link ModelParameterKeys#MODEL_KEYS}) — a model id means nothing to another
     * vendor, and an inherited {@code modelName} would override the summary
     * provider's own default. Everything else (temperature, maxTokens, timeout, …)
     * is vendor-neutral and safe to carry across a provider boundary.
     */
    private static final Set<String> PROVIDER_BOUND_PARAMETERS = Stream.concat(Stream.of(
            "apiKey", "accessToken", "authToken", "nonAzureApiKey", "signingSecret", "appPassword", "botToken",
            "baseUrl", "endpoint",
            "compartmentId", "configProfile", "projectId", "region", "location"),
            ModelParameterKeys.MODEL_KEYS.stream()).collect(Collectors.toUnmodifiableSet());

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
                    + "Credentials, endpoints and model ids never cross a provider boundary; configure them for '%s' "
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
                                                  Map<String, String> processedParams, FormatRetryRunner.Policy retryPolicy,
                                                  FormatRetryRunner.ReaskListener retryListener, IWritableConversationStep currentStep,
                                                  FormatRetryRunner.ReaskGate gate)
            throws LifecycleException, ChatModelRegistry.UnsupportedLlmTaskException {
        // R5: a task that may re-ask its final answer buffers it (no streaming bridge).
        var toolLoopBridge = retryPolicy != null
                ? null
                : createToolLoopStreamingBridge(eventSink, addToOutputExplicitlyFalse, resolvedType, processedParams, task);
        var agentResult = agentOrchestrator.executeIfToolsEnabled(toolLoopBridge != null ? toolLoopBridge : chatModel, systemMessage,
                new ArrayList<>(chatMessagesWithoutSystem), task,
                memory, effectiveToolApprovals, llmTaskIndex, toolTranscriptMaxBytes, jsonPolicy);
        if (agentResult == null) {
            return null;
        }
        if (retryPolicy != null) {
            // Only the final model call is re-asked, over a transcript that already holds
            // the tool results; the loop and its tools are never re-run.
            // The loop's tool cost is spent as well; the runner prices only tokens.
            var loopPolicy = retryPolicy.withSpend(retryPolicy.spend().plus(asDouble(agentResult.responseMetadata().get("toolCostUsd"))));
            var recovered = formatRetryRunner.resolve(loopPolicy,
                    new FormatRetryRunner.Attempt(agentResult.response(), agentResult.responseMetadata()),
                    CascadingModelExecutor.toolTranscript(systemMessage, chatMessagesWithoutSystem, agentResult.toolExchange()),
                    (msgs, maxTokens) -> {
                        var reply = agentOrchestrator.reaskFinalAnswer(chatModel, msgs, maxTokens, task, memory, jsonPolicy);
                        if (reply == null) {
                            throw new LifecycleException("The tool loop cannot re-ask its final answer");
                        }
                        return new FormatRetryRunner.Attempt(reply.response(), reply.responseMetadata());
                    }, turnBudget(memory.getTurnDeadline()), gate, retryListener);
            recordRetry(currentStep, task, recovered);
            if (recovered.reasks() > 0) {
                Map<String, Object> metadata = new HashMap<>(agentResult.responseMetadata());
                if (recovered.attempt().metadata() != null && recovered.attempt().metadata().get("tokenUsage") != null) {
                    metadata.put("tokenUsage", recovered.attempt().metadata().get("tokenUsage"));
                }
                agentResult = new AgentOrchestrator.ExecutionResult(recovered.attempt().text(), agentResult.trace(), metadata,
                        agentResult.toolExchange());
            }
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
        return new AgentModeOutcome(responseContent, trace, responseMetadata, agentResult.toolExchange());
    }

    /**
     * What the tool loop produced, ready for the caller's four locals — plus the
     * tool exchange, so an output guardrail's reprompt can re-ask the final answer
     * over the transcript without re-running a tool.
     */
    private record AgentModeOutcome(String response, List<Map<String, Object>> trace, Map<String, Object> responseMetadata,
            List<ChatMessage> toolExchange) {
    }

    // === Guardrails (WS4) ===

    private static GuardrailContext guardrailContext(IConversationMemory memory, Task task, List<ChatMessage> messages) {
        return guardrailContext(memory, task, messages, null);
    }

    /**
     * The guardrail context of this turn: its messages, the context retrieval
     * returned (output phase; null when none or unknown, e.g. a resumed turn) and
     * the turn deadline, which a model-backed guardrail must not outlive.
     */
    private static GuardrailContext guardrailContext(IConversationMemory memory, Task task, List<ChatMessage> messages, String retrievedContext) {
        return new GuardrailContext(null, task.getId(), memory.getConversationId(), memory.getAgentId(), null, messages,
                retrievedContext != null && !retrievedContext.isEmpty() ? retrievedContext : null, memory.getTurnDeadline());
    }

    /**
     * Records one guardrail outcome: appended to the step's
     * {@code audit:guardrail:<phase>:<type>} list (collected into the audit ledger
     * entry) and sent as a {@code guardrail_triggered} SSE event.
     */
    private void recordGuardrailOutcome(IConversationMemory memory, IWritableConversationStep currentStep, Task task, GuardrailOutcome outcome) {
        String key = MemoryKeys.AUDIT_GUARDRAIL_PREFIX + outcome.phase().label() + ":" + outcome.type();
        List<Object> records = new ArrayList<>();
        IData<List<Object>> existing = currentStep.getLatestData(key);
        if (existing != null && existing.getResult() != null) {
            records.addAll(existing.getResult());
        }
        records.add(outcome.toAuditRecord(task.getId()));
        currentStep.storeData(dataFactory.createData(key, records));
        var sink = memory.getEventSink();
        if (sink != null) {
            sink.onGuardrailTriggered(outcome.type(), outcome.phase().label(), outcome.action().label(), task.getId() != null ? task.getId() : "");
        }
    }

    /**
     * Answers a turn whose input a guardrail blocked: the block message becomes the
     * task's response — stored, visible to output templates under the response
     * object name, shown and streamed unless {@code addToOutput} is explicitly
     * false. The model is not called and {@code preRequest} does not run;
     * {@code postResponse} does, exactly as after any other fallback (an output
     * block, a spend refusal, {@code onError}) — for a {@code convertToObject} task
     * with {@code addToOutput: false} it is what turns the
     * {@code fallbackField}-wrapped block message into the user's answer. It is
     * served as a fallback: the step's {@code llm:fallback:<taskId>} flag keeps the
     * block message out of later turns' history, and its
     * {@code llm:input_withheld:<taskId>} flag keeps the refused message out of
     * every later model-facing history.
     */
    private void answerInputBlocked(IConversationMemory memory, IWritableConversationStep currentStep, Task task, Map<String, String> processedParams,
                                    Map<String, Object> templateDataObjects, String blockMessage)
            throws IOException, ITemplatingEngine.TemplateEngineException {
        var served = guardrailBlock(task, currentStep, blockMessage, Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT)));
        markInputWithheld(currentStep, task);
        currentStep.storeData(dataFactory.createData(KEY_LANGCHAIN + ":" + task.getType() + ":" + task.getId(), served.content()));
        String responseObjectName = isNullOrEmpty(task.getResponseObjectName()) ? task.getId() : task.getResponseObjectName();
        if (responseObjectName != null) {
            templateDataObjects.put(responseObjectName, served.object());
        }
        if (!"false".equalsIgnoreCase(processedParams.get(KEY_ADD_TO_OUTPUT))) {
            currentStep.storeData(dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), blockMessage));
            var outputItem = new TextOutputItem(blockMessage, 0);
            outputItem.setFallback(true);
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
            if (memory.getEventSink() != null) {
                memory.getEventSink().onToken(blockMessage);
            }
        }
        prePostUtils.runPostResponse(memory, task.getPostResponse(), templateDataObjects, 200, false);
    }

    /**
     * Flags the step {@code llm:input_withheld:<taskId>}: no later model-facing
     * history (message window, rolling summary, recall tool) carries this turn's
     * user message — see {@code ConversationLogGenerator.isInputWithheldStep}.
     */
    private void markInputWithheld(IWritableConversationStep currentStep, Task task) {
        String id = task.getId() != null ? task.getId() : "default";
        currentStep.storeData(dataFactory.createData(MemoryKeys.LLM_INPUT_WITHHELD_PREFIX + id, Boolean.TRUE));
    }

    /**
     * The input guardrails' rewrites applied to a rebuilt prompt; unchanged without
     * them.
     */
    private List<ChatMessage> reapplyInputScreening(IConversationMemory memory, Task task, GuardrailService.InputResult screened,
                                                    List<ChatMessage> rebuilt) {
        if (screened == null || rebuilt == null || guardrailService == null) {
            return rebuilt;
        }
        return guardrailService.reapply(task.getInputGuardrails(), rebuilt, screened, guardrailContext(memory, task, rebuilt));
    }

    /**
     * A guardrail's block, served as a fallback: the engine's text, not parsed as
     * JSON, not recorded as what the model said, flagged on the step so later
     * history leaves it out, and wrapped under {@code fallbackField} for a
     * {@code convertToObject} task.
     */
    private LlmFallbackHandler.Fallback guardrailBlock(Task task, IWritableConversationStep currentStep, String blockMessage,
                                                       boolean convertObject) {
        return fallbackHandler.serveText(task, currentStep, blockMessage, convertObject);
    }

    /**
     * Folds the token usage of guardrail reprompts into the answer's metadata, so
     * they are counted and priced like every other model call of the turn.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> foldRepromptUsage(Map<String, Object> responseMetadata, List<Map<String, Object>> reprompts) {
        if (reprompts == null || reprompts.isEmpty()) {
            return responseMetadata;
        }
        Map<String, Object> metadata = responseMetadata != null ? new HashMap<>(responseMetadata) : new HashMap<>();
        Map<String, Object> total = new LinkedHashMap<>();
        if (metadata.get("tokenUsage") instanceof Map<?, ?> existing) {
            total.putAll((Map<String, Object>) existing);
        }
        Map<String, Object> repromptUsage = new LinkedHashMap<>();
        double cascadeRepromptCost = 0.0;
        for (Map<String, Object> reprompt : reprompts) {
            if (reprompt == null) {
                continue;
            }
            if (reprompt.get("tokenUsage") instanceof Map<?, ?> usage) {
                CascadingModelExecutor.mergeTokenUsage(repromptUsage, (Map<String, Object>) usage);
            }
            cascadeRepromptCost += asDouble(reprompt.get(KEY_REPROMPT_COST));
        }
        if (!repromptUsage.isEmpty()) {
            CascadingModelExecutor.mergeTokenUsage(total, repromptUsage);
            metadata.put("tokenUsage", total);
        }
        // Under the cascade, cost is not derived from tokenUsage but carried in
        // cascadeCostUsd (accumulateAuditEvidence) — so the reprompts' cost, priced
        // with the winning step's prices, is added there.
        if (metadata.containsKey("cascadeCostUsd") && cascadeRepromptCost > 0.0) {
            metadata.put("cascadeCostUsd", asDouble(metadata.get("cascadeCostUsd")) + cascadeRepromptCost);
        }
        return metadata;
    }

    /**
     * Whether a guardrail reprompt may start: the turn has at least
     * {@code minAttemptMs} (the task's {@code responseValidation}, or its default)
     * left — the same rule as a format re-ask (R5).
     */
    private static boolean repromptMayStart(IConversationMemory memory, Task task) {
        long minAttemptMs = task.getResponseValidation() != null
                ? task.getResponseValidation().getMinAttemptMs()
                : ResponseValidation.DEFAULT_MIN_ATTEMPT_MS;
        return turnBudget(memory.getTurnDeadline()).remainingMs() >= minAttemptMs;
    }

    /**
     * How a guardrail reprompt consults the per-model circuit breaker (R8): a fresh
     * ticket per reprompt, settled with the reprompt's own outcome — the primary
     * call's ticket is already settled by then. {@link #NONE} for an unguarded task
     * and for the HITL resume, which neither consults nor settles a circuit (R12).
     *
     * @param acquire
     *            asks the breaker; a denied ticket stops the reprompts
     * @param params
     *            the model's parameters, for classifying an invalid-JSON reply
     * @param convertObject
     *            whether the reply must be a JSON object
     */
    private record RepromptCircuit(Supplier<LlmCircuitBreakers.Ticket> acquire, Map<String, String> params, boolean convertObject) {
        static final RepromptCircuit NONE = new RepromptCircuit(() -> LlmCircuitBreakers.Ticket.DISABLED, Map.of(), false);
    }

    /**
     * A reprompter over {@code baseMessages}: the request, the rejected answer as
     * the assistant's turn, the corrective message as a user turn — the corrective
     * shape of a format re-ask ({@link FormatRetryRunner#withCorrective}). Returns
     * no answer (the guardrail then blocks) when the turn is out of time or the
     * model's circuit is open.
     */
    private Reprompter reprompter(List<ChatMessage> baseMessages, FormatRetryRunner.Asker asker, IConversationMemory memory, Task task,
                                  RepromptCircuit circuit) {
        return (rejectedReply, correctiveMessage) -> {
            if (!repromptMayStart(memory, task)) {
                return null;
            }
            // A reprompt is another priced model call: the spend gate is asked again, so
            // a turn that reached its tenant budget or conversation ceiling stops
            // re-asking (and the guardrail blocks).
            if (spendGate != null && spendGate.check(memory) != null) {
                LOGGER.infof("Guardrail reprompt for task '%s' skipped: a spend limit is reached", task.getId());
                return null;
            }
            var ticket = circuit.acquire().get();
            if (!ticket.allowed()) {
                recordRecovery(memory, LlmRecoveryLog.CIRCUIT_SKIP, "skipped", ticket.denial().failure().label(), 0L);
                LOGGER.infof("Guardrail reprompt for task '%s' skipped: the model's circuit is open", task.getId());
                return null;
            }
            var request = FormatRetryRunner.withCorrective(baseMessages, rejectedReply, "", correctiveMessage);
            FormatRetryRunner.Attempt attempt;
            try {
                attempt = asker.ask(request, null);
            } catch (LifecycleException | RuntimeException e) {
                settleCircuit(ticket, e);
                throw e;
            }
            settleCircuit(ticket, attempt.text(), circuit.convertObject(), circuit.params(), task);
            return new Reprompter.Attempt(attempt.text(), attempt.metadata(), repromptCost(attempt.metadata(), task));
        };
    }

    /**
     * What one reprompt cost: a cascade step's own price
     * ({@code KEY_REPROMPT_COST}) or the reply's tokens at the task's prices.
     */
    private static double repromptCost(Map<String, Object> metadata, Task task) {
        if (metadata == null) {
            return 0.0;
        }
        if (metadata.containsKey(KEY_REPROMPT_COST)) {
            return asDouble(metadata.get(KEY_REPROMPT_COST));
        }
        return TokenPricing.cost(task.getInputPricePer1M(), task.getOutputPricePer1M(),
                metadata.get("tokenUsage") instanceof Map<?, ?> usage ? usage : null);
    }

    /** Plain chat: the same model over the same request. */
    private Reprompter plainReprompter(ChatModel chatModel, List<ChatMessage> requestMessages, Task task, IConversationMemory memory,
                                       JsonResponseFormatPolicy jsonPolicy, RepromptCircuit circuit) {
        return reprompter(requestMessages, (msgs, maxTokens) -> {
            var result = legacyChatExecutor.execute(chatModel, msgs, task, jsonPolicy, maxTokens, memory.getTurnDeadline());
            return new FormatRetryRunner.Attempt(result.response(), result.responseMetadata());
        }, memory, task, circuit);
    }

    /**
     * Tool loop: only the final model call is re-asked, over the transcript that
     * already holds the tool results — no tool runs twice.
     */
    private Reprompter toolLoopReprompter(ChatModel chatModel, String systemMessage, List<ChatMessage> historyOnly, List<ChatMessage> toolExchange,
                                          Task task, IConversationMemory memory, JsonResponseFormatPolicy jsonPolicy, RepromptCircuit circuit) {
        List<ChatMessage> transcript = CascadingModelExecutor.toolTranscript(systemMessage, historyOnly,
                toolExchange != null ? toolExchange : List.of());
        return transcriptReprompter(chatModel, transcript, task, memory, jsonPolicy, circuit);
    }

    /** A final-answer re-ask over a tool transcript (live or resumed). */
    private Reprompter transcriptReprompter(ChatModel chatModel, List<ChatMessage> transcript, Task task, IConversationMemory memory,
                                            JsonResponseFormatPolicy jsonPolicy, RepromptCircuit circuit) {
        return reprompter(transcript, (msgs, maxTokens) -> {
            var reply = agentOrchestrator.reaskFinalAnswer(chatModel, msgs, maxTokens, task, memory, jsonPolicy);
            if (reply == null) {
                throw new LifecycleException("The tool loop cannot re-ask its final answer");
            }
            return new FormatRetryRunner.Attempt(reply.response() != null ? reply.response() : "", reply.responseMetadata());
        }, memory, task, circuit);
    }

    /**
     * Cascade: the reprompt goes to the step whose answer was accepted, built
     * lazily — only when a guardrail actually reprompts — and admitted by that
     * step's circuit, keyed as the cascade keys it. Agent-mode steps re-ask their
     * final answer over their tool transcript.
     */
    private Reprompter cascadeReprompter(LlmConfiguration.ModelCascadeConfig cascadeConfig, CascadingModelExecutor.CascadeResult cascadeResult,
                                         Task task, Map<String, String> processedParams, Map<String, Object> templateDataObjects,
                                         IConversationMemory memory, boolean jsonMode, String agentSystemMessage,
                                         List<ChatMessage> chatMessagesWithoutSystem, List<ChatMessage> requestMessages) {
        int stepIndex = cascadeResult.stepUsed();
        if (stepIndex < 0 || stepIndex >= cascadeConfig.getSteps().size()) {
            return null;
        }
        CascadeStep step = cascadeConfig.getSteps().get(stepIndex);
        var agentResult = cascadeResult.agentResult();
        List<ChatMessage> base = agentResult != null
                ? CascadingModelExecutor.toolTranscript(agentSystemMessage, chatMessagesWithoutSystem, agentResult.toolExchange())
                : requestMessages;
        RepromptCircuit circuit = circuitBreakers == null ? RepromptCircuit.NONE : new RepromptCircuit(() -> {
            var stepModel = cascadingModelExecutor.resolveStepModel(step, task, processedParams, templateDataObjects, memory, "Cascade step");
            String modelName = globalVariableResolver.resolveValue(resolveModelName(stepModel.params(), stepModel.modelType()));
            return circuitBreakers.acquire(
                    new LlmCircuitBreakers.Key(memory.getAgentId(), memory.getAgentVersion(), stepModel.modelType(), modelName),
                    task.getCircuitBreaker());
        }, processedParams, jsonMode);
        return reprompter(base, (msgs, maxTokens) -> {
            var stepModel = cascadingModelExecutor.resolveStepModel(step, task, processedParams, templateDataObjects, memory, "Cascade step");
            ChatModel stepChat;
            try {
                stepChat = chatModelRegistry.getOrCreate(stepModel.modelType(), stepModel.params());
            } catch (ChatModelRegistry.UnsupportedLlmTaskException e) {
                throw new LifecycleException(e.getLocalizedMessage(), e);
            }
            var stepPolicy = JsonResponseFormatPolicy.of(jsonMode, stepModel.modelType(), task.getJsonResponseFormat(),
                    stepModel.params().get(KEY_RESPONSE_SCHEMA), capabilityParams(stepModel.modelType(), stepModel.params()));
            String text;
            Map<String, Object> metadata;
            if (agentResult != null) {
                var reply = agentOrchestrator.reaskFinalAnswer(stepChat, msgs, maxTokens, task, memory, stepPolicy);
                if (reply == null) {
                    throw new LifecycleException("The tool loop cannot re-ask its final answer");
                }
                text = reply.response() != null ? reply.response() : "";
                metadata = reply.responseMetadata();
            } else {
                var result = legacyChatExecutor.execute(stepChat, msgs, task, stepPolicy, maxTokens, memory.getTurnDeadline());
                text = result.response();
                metadata = result.responseMetadata();
            }
            Map<String, Object> priced = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            Double inPrice = step.getInputPricePer1M() != null ? step.getInputPricePer1M() : cascadeConfig.getInputPricePer1M();
            Double outPrice = step.getOutputPricePer1M() != null ? step.getOutputPricePer1M() : cascadeConfig.getOutputPricePer1M();
            priced.put(KEY_REPROMPT_COST, TokenPricing.cost(inPrice, outPrice, priced.get("tokenUsage") instanceof Map<?, ?> usage ? usage : null));
            return new FormatRetryRunner.Attempt(text, priced);
        }, memory, task, circuit);
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

                        LOGGER.debugf("httpCall RAG '%s' executed: keys=%s, size=%d", httpCallName, result.keySet(), serialized.length());
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
            URI uri = ResourceUris.require(uriObj, ID);

            try {
                LlmConfiguration llmConfiguration = resourceClientLibrary.getResource(uri, LlmConfiguration.class);
                // Fail fast on cascade misconfiguration at deploy time (#validation).
                CascadeConfigValidator.validate(llmConfiguration);
                validateGuardrails(llmConfiguration);
                // Self-hosted endpoints: required fields and capability flags.
                OpenAiCompatibleConfigValidator.validate(llmConfiguration);
                warnOnDeprecatedParameters(llmConfiguration);
                return llmConfiguration;
            } catch (ServiceException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new WorkflowConfigurationException(e.getLocalizedMessage(), e);
            }
        }

        throw new WorkflowConfigurationException("No resource URI has been defined! [LlmConfiguration]");
    }

    /**
     * Fails the deploy on a guardrail configuration that cannot work at all (each
     * type decides, {@code ContentGuardrail.validate}) — e.g. a classifier with a
     * custom unsafe label but no safe label, which could never let a turn through.
     */
    void validateGuardrails(LlmConfiguration llmConfiguration) throws WorkflowConfigurationException {
        if (guardrailService == null || llmConfiguration == null || llmConfiguration.tasks() == null) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (var task : llmConfiguration.tasks()) {
            String taskId = task.getId() != null ? task.getId() : "<unnamed>";
            for (String problem : guardrailService.validate("inputGuardrails", task.getInputGuardrails())) {
                problems.add("task '" + taskId + "' " + problem);
            }
            for (String problem : guardrailService.validate("outputGuardrails", task.getOutputGuardrails())) {
                problems.add("task '" + taskId + "' " + problem);
            }
            for (String warning : guardrailService.warnings("inputGuardrails", task.getInputGuardrails())) {
                LOGGER.warnf("LLM task '%s' %s", taskId, warning);
            }
            for (String warning : guardrailService.warnings("outputGuardrails", task.getOutputGuardrails())) {
                LOGGER.warnf("LLM task '%s' %s", taskId, warning);
            }
            warnSelfJudging(task, taskId);
        }
        if (!problems.isEmpty()) {
            throw new WorkflowConfigurationException("Invalid guardrail configuration: " + String.join("; ", problems));
        }
    }

    /**
     * A {@code judge} grading the task's own model is biased toward its own
     * answers: worth a note at deploy time, not a refusal (a different prompt still
     * catches a lot). Compared on the configured provider and model name.
     */
    private static void warnSelfJudging(Task task, String taskId) {
        if (task.getOutputGuardrails() == null || task.getParameters() == null) {
            return;
        }
        String taskModel = resolveModelName(task.getParameters());
        if (task.getType() == null || taskModel == null) {
            return;
        }
        for (GuardrailConfig guardrail : task.getOutputGuardrails()) {
            if (guardrail == null || !JudgeGuardrail.TYPE.equals(guardrail.getType()) || guardrail.getParameters() == null) {
                continue;
            }
            Map<String, String> p = guardrail.getParameters();
            if (task.getType().trim().equalsIgnoreCase(String.valueOf(p.get("provider")).trim())
                    && taskModel.trim().equals(String.valueOf(resolveModelName(p)).trim())) {
                LOGGER.warnf("LLM task '%s' has a judge guardrail on its own model '%s' — a model grading its own answers is biased; "
                        + "consider a different judge model", taskId, taskModel);
            }
        }
    }

    /**
     * Reports deprecated parameters once per configuration load, at the moment the
     * configuration is read.
     * <p>
     * {@code includeFirstAgentMessage} is deprecated: it exists to satisfy an
     * Anthropic rule that a conversation may not open on an assistant turn, and the
     * Messages API no longer documents that rule. It is still honoured -- agent
     * behaviour lives in stored JSON, and silently ignoring a parameter an author
     * set on purpose would start sending a greeting they chose to withhold with no
     * diagnostic.
     * <p>
     * <b>Here rather than in {@code execute}</b>, and static rather than instance.
     * An {@link ILifecycleTask} is an application-scoped singleton shared by every
     * conversation and MUST be stateless (AGENTS.md §4.1 rule 2), so the obvious
     * "warn once, remember that we did" needs a field this class may not have.
     * Keying such a field on the task id would have been wrong twice over: two
     * tasks that both omit an id collapse to the same key, so the second one would
     * never have warned at all.
     * <p>
     * Config load is the honest boundary anyway. It is where the mistake is
     * fixable, it is already where deploy-time validation runs, and it visits every
     * task in the document individually -- including two that share, or omit, an
     * id.
     */
    static void warnOnDeprecatedParameters(LlmConfiguration llmConfiguration) {
        // A record: the accessor is tasks(), not getTasks().
        if (llmConfiguration == null || llmConfiguration.tasks() == null) {
            return;
        }
        for (var task : llmConfiguration.tasks()) {
            if (task == null || task.getParameters() == null) {
                continue;
            }
            if (isNullOrEmpty(task.getParameters().get(KEY_INCLUDE_FIRST_AGENT_MESSAGE))) {
                continue;
            }
            LOGGER.warnf("LLM task '%s' sets the deprecated parameter '%s'. It exists to satisfy an Anthropic "
                    + "first-message rule that no longer applies, and it is honoured unchanged for now. Remove it from new "
                    + "configurations; keep it only if this agent must genuinely withhold its opening greeting.",
                    sanitize(task.getId() != null ? task.getId() : "<unnamed>"), KEY_INCLUDE_FIRST_AGENT_MESSAGE);
        }
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
     * As {@link #resolveModelName(Map)}, but a named OpenAI-compatible provider
     * (xAI, DeepSeek, ...) configured without a model resolves to its preset's
     * default model — the same one its builder will send. Without this, vision
     * forwarding and the audit/cost records saw no model at all.
     *
     * @param resolvedType
     *            the task type after global-variable resolution
     */
    public static String resolveModelName(Map<String, String> processedParams, String resolvedType) {
        String name = resolveModelName(processedParams);
        if (name == null || name.isBlank()) {
            return OpenAiCompatibleProviders.find(resolvedType).map(OpenAiCompatibleProvider::defaultModel).orElse(name);
        }
        return name;
    }

    /**
     * The model name with its {@code ${vars:...}} references resolved — the name
     * the model was actually built with ({@code ChatModelRegistry} resolves the
     * same references late). Used for everything that records or keys on the model
     * (audit, circuit, metrics), so a task configured with {@code "modelName":
     * "${vars:gemini-model}"} no longer reports the literal template.
     */
    private String resolveModelNameOf(Map<String, String> processedParams, String resolvedType) {
        return resolveModelName(processedParams, resolvedType, globalVariableResolver);
    }

    /**
     * As {@link #resolveModelName(Map, String)}, with {@code ${vars:...}}
     * references resolved. An unresolvable reference (unknown variable, a resolver
     * error) leaves the raw text: the audit then says exactly what was configured
     * instead of failing the turn over a label.
     */
    static String resolveModelName(Map<String, String> processedParams, String resolvedType, GlobalVariableResolver resolver) {
        String name = resolveModelName(processedParams, resolvedType);
        if (resolver == null || !GlobalVariableResolver.containsReference(name)) {
            return name;
        }
        try {
            String resolved = resolver.resolveValue(name);
            return resolved != null ? resolved : name;
        } catch (RuntimeException e) {
            return name;
        }
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
