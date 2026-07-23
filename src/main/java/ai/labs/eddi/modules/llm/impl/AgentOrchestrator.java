/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.setup.AgentSetupService;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.ToolNameResolver;
import ai.labs.eddi.modules.llm.tools.UserMemoryTool;
import ai.labs.eddi.modules.llm.tools.ConversationRecallTool;
import ai.labs.eddi.modules.llm.tools.CreateSubAgentTool;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.FindAgentsByCapabilityTool;
import ai.labs.eddi.modules.llm.tools.TeardownAgentTool;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.Metrics;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private static final String MCPCALLS_TYPE = "eddi://ai.labs.mcpcalls";

    /** Well-known data keys for dynamic agent lifecycle tracking. */
    public static final String KEY_DYNAMIC_CREATED_AGENT_IDS = "dynamic:created_agent_ids";
    public static final String KEY_DYNAMIC_RETAINED_AGENT_IDS = "dynamic:retained_agent_ids";

    // === Tool-level HITL (tool-approval gate) ===
    /** Step-data key holding this turn's cumulative gated-pause count (int). */
    static final String KEY_TOOL_PAUSE_COUNT = "hitl:tool_pause_count";
    /** Default max pauses per turn when the config does not specify one. */
    private static final int DEFAULT_MAX_PAUSES_PER_TURN = 3;
    /** Default transcript serialization cap (bytes). */
    private static final int DEFAULT_TRANSCRIPT_MAX_BYTES = PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT;
    /** Approver-facing pause reason cap (chars). */
    private static final int PAUSE_REASON_MAX_CHARS = 500;

    /**
     * Fallback for {@code LlmConfiguration.Task#getMaxToolContextTokens()} when a
     * stored config carries an explicit {@code null} (the POJO's own field default
     * is the same number, so this only covers deserialized nulls).
     *
     * @see #enforceToolContextBudget
     */
    static final int DEFAULT_MAX_TOOL_CONTEXT_TOKENS = 60_000;

    /**
     * Deployment-wide fallback for {@code LlmConfiguration.Task#getEnforceBudget()}
     * ({@code eddi.tools.budget.enforce-by-default}, default {@code true}).
     * <p>
     * The default is {@code true} so that a stored {@code maxBudgetPerConversation}
     * keeps doing what it did before {@code enforceBudget} existed. The gate only
     * fires when a ceiling is configured at all, so this flag is unobservable for
     * agents that set none. Defaulting it to {@code false} instead — as this field
     * originally did — silently voided every ceiling that <em>was</em> being
     * enforced: the "no built-in was ever priced above $0.00" reasoning holds only
     * for built-ins, and http/MCP/A2A/dynamic tools dispatch under their configured
     * name, so an agent with a tool called {@code websearch}, {@code webscraper} or
     * {@code pdfreader} was priced and refused on the previous release.
     * <p>
     * Opting out is explicit and still available in both directions: per task with
     * {@code "enforceBudget": false}, or deployment-wide with
     * {@code eddi.tools.budget.enforce-by-default=false}.
     * <p>
     * Read through {@link ConfigProvider} rather than {@code @ConfigProperty}
     * because AgentOrchestrator is not a CDI bean — {@code LlmTask} constructs it
     * with {@code new}, so an injection annotation here would never fire and the
     * field would stay at its initializer while looking configurable.
     */
    private static final boolean BUDGET_ENFORCE_DEFAULT = resolveBudgetEnforceDefault();

    private static boolean resolveBudgetEnforceDefault() {
        try {
            return ConfigProvider.getConfig()
                    .getOptionalValue("eddi.tools.budget.enforce-by-default", Boolean.class)
                    .orElse(true);
        } catch (Exception e) {
            // No MicroProfile config available (plain unit test JVM): fall back to the
            // documented default rather than to "unenforced", so a missing config never
            // turns a configured ceiling off.
            return true;
        }
    }

    // Stateless helpers — safe to instantiate directly (no CDI needed).
    private final ToolApprovalGate toolApprovalGate = new ToolApprovalGate();
    private final ChatTranscriptCodec chatTranscriptCodec = new ChatTranscriptCodec();

    // Built-in tools
    private final CalculatorTool calculatorTool;
    private final DateTimeTool dateTimeTool;
    private final WebSearchTool webSearchTool;
    private final DataFormatterTool dataFormatterTool;
    private final WebScraperTool webScraperTool;
    private final TextSummarizerTool textSummarizerTool;
    private final PdfReaderTool pdfReaderTool;
    private final WeatherTool weatherTool;
    private final FetchToolResponsePageTool fetchToolResponsePageTool;
    private final ToolExecutionService toolExecutionService;
    private final McpToolProviderManager mcpToolProviderManager;
    private final A2AToolProviderManager a2aToolProviderManager;

    // For httpcall auto-discovery from workflow
    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IApiCallExecutor apiCallExecutor;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;
    private final IUserMemoryStore userMemoryStore;
    private final ToolResponseTruncator toolResponseTruncator;
    private final TenantQuotaService tenantQuotaService;
    private final MemorySnapshotService memorySnapshotService;

    // Dynamic agent creation dependencies
    private final AgentSetupService agentSetupService;
    private final CapabilityRegistryService capabilityRegistryService;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;

    // Set once after construction by LlmTask (@PostConstruct), past the
    // constructor's final-field freeze; `volatile` publishes this write-once
    // field to the per-turn reader threads (see setAttachmentServices).
    private volatile IAttachmentStore attachmentStore;
    private volatile AttachmentTextExtractor attachmentTextExtractor;

    /**
     * Provide the attachment services used to build the {@code readAttachment}
     * tool. Called by {@code LlmTask} after CDI injection completes; when unset
     * (e.g. in isolated unit tests) the tool is simply never added.
     */
    void setAttachmentServices(IAttachmentStore attachmentStore, AttachmentTextExtractor attachmentTextExtractor) {
        this.attachmentStore = attachmentStore;
        this.attachmentTextExtractor = attachmentTextExtractor;
    }

    // HITL tool-approval resume dependencies (Task 9)
    private final IHitlToolJournalStore journalStore;
    private final ConversationHistoryBuilder conversationHistoryBuilder;

    /**
     * The SAME estimator factory {@code LlmTask} uses to window conversation
     * history, reused here to meter the in-turn tool context. Deliberately not a
     * second estimator: one accounting rule for both halves of the request means a
     * budget expressed in tokens keeps meaning the same thing wherever it is set.
     */
    private final TokenCounterFactory tokenCounterFactory;

    AgentOrchestrator(CalculatorTool calculatorTool, DateTimeTool dateTimeTool, WebSearchTool webSearchTool, DataFormatterTool dataFormatterTool,
            WebScraperTool webScraperTool, TextSummarizerTool textSummarizerTool, PdfReaderTool pdfReaderTool, WeatherTool weatherTool,
            FetchToolResponsePageTool fetchToolResponsePageTool,
            ToolExecutionService toolExecutionService, McpToolProviderManager mcpToolProviderManager, A2AToolProviderManager a2aToolProviderManager,
            IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore, IResourceClientLibrary resourceClientLibrary,
            IApiCallExecutor apiCallExecutor, IJsonSerialization jsonSerialization, IMemoryItemConverter memoryItemConverter,
            IUserMemoryStore userMemoryStore, ToolResponseTruncator toolResponseTruncator, TenantQuotaService tenantQuotaService,
            MemorySnapshotService memorySnapshotService,
            AgentSetupService agentSetupService, CapabilityRegistryService capabilityRegistryService,
            IConversationService conversationService, IAgentFactory agentFactory, IAgentStore agentStore,
            IHitlToolJournalStore journalStore, ConversationHistoryBuilder conversationHistoryBuilder,
            TokenCounterFactory tokenCounterFactory) {
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
        this.a2aToolProviderManager = a2aToolProviderManager;
        this.fetchToolResponsePageTool = fetchToolResponsePageTool;
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.apiCallExecutor = apiCallExecutor;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.userMemoryStore = userMemoryStore;
        this.toolResponseTruncator = toolResponseTruncator;
        this.tenantQuotaService = tenantQuotaService;
        this.memorySnapshotService = memorySnapshotService;
        this.agentSetupService = agentSetupService;
        this.capabilityRegistryService = capabilityRegistryService;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.journalStore = journalStore;
        this.conversationHistoryBuilder = conversationHistoryBuilder;
        this.tokenCounterFactory = tokenCounterFactory;
    }

    /**
     * Result of an agent execution.
     *
     * @param response
     *            the final LLM text response
     * @param trace
     *            list of tool call/result trace entries for debugging
     * @param responseMetadata
     *            metadata about the execution (aggregate token usage across
     *            tool-loop iterations). Never null; empty when unavailable.
     */
    record ExecutionResult(String response, List<Map<String, Object>> trace, Map<String, Object> responseMetadata) {
        /** Convenience constructor — no response metadata (empty map). */
        ExecutionResult(String response, List<Map<String, Object>> trace) {
            this(response, trace, Map.of());
        }
    }

    /**
     * Collect enabled tools, append tool instructions to system message, and
     * execute the tool-calling loop.
     *
     * @return null if no tools are enabled (caller should use legacy mode),
     *         otherwise the execution result
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, LlmConfiguration.Task task,
                                          IConversationMemory memory)
            throws LifecycleException {
        // Backward-compatible overload: no tool-approval gate (config null, index -1).
        return executeIfToolsEnabled(chatModel, systemMessage, chatMessages, task, memory, null, -1);
    }

    /**
     * Backward-compatible overload without an explicit JSON response-format policy
     * — the tool loop's requests carry no response format.
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, LlmConfiguration.Task task,
                                          IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex,
                                          int transcriptMaxBytes)
            throws LifecycleException {
        return executeIfToolsEnabled(chatModel, systemMessage, chatMessages, task, memory, effectiveToolApprovals, llmTaskIndex, transcriptMaxBytes,
                JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * Collect enabled tools and run the tool-calling loop with the tool-approval
     * gate active.
     *
     * @param effectiveToolApprovals
     *            the resolved tool-approval config (task override or agent
     *            default); {@code null}/empty means the gate is inert —
     *            byte-identical to the pre-HITL behavior.
     * @param llmTaskIndex
     *            the position of {@code task} in the llmConfig task list — recorded
     *            on the pending batch so resume re-enters the correct task.
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, LlmConfiguration.Task task,
                                          IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex)
            throws LifecycleException {
        // Backward-compatible overload: transcript cap defaults to the constant
        // (callers that have not been updated to pass the configured value keep the
        // exact pre-existing behavior).
        return executeIfToolsEnabled(chatModel, systemMessage, chatMessages, task, memory, effectiveToolApprovals, llmTaskIndex,
                DEFAULT_TRANSCRIPT_MAX_BYTES);
    }

    /**
     * Collect enabled tools and run the tool-calling loop with the tool-approval
     * gate active.
     *
     * @param effectiveToolApprovals
     *            the resolved tool-approval config (task override or agent
     *            default); {@code null}/empty means the gate is inert —
     *            byte-identical to the pre-HITL behavior.
     * @param llmTaskIndex
     *            the position of {@code task} in the llmConfig task list — recorded
     *            on the pending batch so resume re-enters the correct task.
     * @param transcriptMaxBytes
     *            the configured cap (bytes) for serializing the frozen transcript
     *            into a {@link PendingToolCallBatch} on a tool pause — resolved by
     *            {@code LlmTask} from {@code eddi.hitl.tool.transcript-max-bytes}
     *            (default
     *            {@link PendingToolCallBatch#TRANSCRIPT_MAX_BYTES_DEFAULT}).
     * @param jsonPolicy
     *            decides whether the tool-loop's model requests carry
     *            {@code ResponseFormat.JSON}. Resolved per request against whether
     *            that request actually carries tool specifications, so a provider
     *            that rejects JSON mode alongside function calling (Gemini) is
     *            never sent both.
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, LlmConfiguration.Task task,
                                          IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex,
                                          int transcriptMaxBytes, JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {

        // Discover + register all tools (built-in + http + mcp + a2a) — the SAME
        // prologue the resume path uses.
        ToolSetup setup = buildToolSetup(task, memory);

        // No tools? Return null — caller should use legacy mode.
        if (setup.toolSpecs().isEmpty()) {
            return null;
        }

        return executeWithTools(chatModel, systemMessage, chatMessages, setup, task, memory, effectiveToolApprovals, llmTaskIndex,
                transcriptMaxBytes, jsonPolicy);
    }

    /**
     * Re-enter the tool-calling loop after a HITL tool pause was resolved by a
     * human. Replays the persisted transcript, applies the per-call verdicts from
     * {@code decision} (approved calls execute at-most-once via the write-ahead
     * journal; rejected calls become synthetic error tool results), then continues
     * the loop until the model produces a final response — or re-pauses on a fresh
     * gated call.
     * <p>
     * <strong>Task 8 ships only the signature.</strong> The body is implemented in
     * Task 9. {@code LlmTask.executeResume} calls this method; the Task-8 unit test
     * mocks the orchestrator, so the stub is never actually reached. The signature
     * here is byte-identical to what Task 9 fills in.
     *
     * @param chatModel
     *            the resolved chat model for the paused task (rebuilt by
     *            {@code LlmTask.executeResume} exactly as the normal path does)
     * @param task
     *            the paused LLM task (identity-bound to {@code batch})
     * @param memory
     *            the live conversation memory
     * @param batch
     *            the interrupted tool-call batch carrying the frozen transcript and
     *            gated calls
     * @param decision
     *            the human decision being applied
     * @param templateDataObjects
     *            the template data map (for post-response and fallback rebuild)
     * @return the execution result (final response + tool trace)
     */
    ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory, PendingToolCallBatch batch,
                                   HitlDecision decision, Map<String, Object> templateDataObjects, boolean toolHitlEnabled)
            throws LifecycleException {
        return resumeToolLoop(chatModel, task, memory, batch, decision, templateDataObjects, toolHitlEnabled, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * As
     * {@link #resumeToolLoop(ChatModel, LlmConfiguration.Task, IConversationMemory, PendingToolCallBatch, HitlDecision, Map, boolean)},
     * but carrying the JSON response-format policy so the continuation's requests
     * match what the live loop would have sent.
     */
    ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory, PendingToolCallBatch batch,
                                   HitlDecision decision, Map<String, Object> templateDataObjects, boolean toolHitlEnabled,
                                   JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {

        String conversationId = memory.getConversationId();
        String pauseEpoch = batch.getPauseEpoch();
        List<Map<String, Object>> trace = new ArrayList<>();

        // Tool-cost baseline, snapshotted BEFORE the verdict loop below — that loop
        // runs every human-approved gated call through executeToolWrapped, which
        // charges the conversation's cost tracker. A baseline taken after it would
        // already contain those charges, so the delta reported as toolCostUsd (and
        // hence the audit ledger's dollar figure) would exclude exactly the calls a
        // human explicitly approved. The live path (executeWithTools) snapshots
        // before any tool runs; this is the same contract.
        double toolCostBefore = conversationToolCost(conversationId);

        // ── Step 2: rebuild tooling via the shared setup (SAME as the live path) ──
        ToolSetup setup = buildToolSetup(task, memory);
        boolean isLazy = task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY;

        // Restore the active-spec surface. For LAZY, reactivate exactly the specs that
        // were active at pause time (activatedToolNames); otherwise the full set.
        List<ToolSpecification> activeSpecs = restoreActiveSpecs(setup, isLazy, batch.getActivatedToolNames());

        // ── Step 1: reconstitute the transcript (primary) or fall back to rebuild ──
        List<ChatMessage> currentMessages = new ArrayList<>();
        boolean transcriptRestored = false;
        if (!batch.isTranscriptOmitted() && batch.getChatTranscriptJson() != null) {
            try {
                currentMessages.addAll(chatTranscriptCodec.deserialize(batch.getChatTranscriptJson()));
                transcriptRestored = true;
            } catch (ChatTranscriptCodec.TranscriptCodecException e) {
                LOGGER.warnf("HITL resume: transcript restore failed (%s) — falling back to history rebuild for conversation '%s'",
                        e.getMessage(), sanitize(conversationId));
            }
        }
        if (!transcriptRestored) {
            currentMessages.addAll(fallbackRebuildMessages(task, memory, batch));
        }
        trace.add(Map.of("type", "hitl_resume", "transcriptRestored", transcriptRestored));

        // ── Step 3: apply verdicts in batch order ──
        Set<String> clearedCallIds = new HashSet<>();
        Map<String, ToolExecutor> toolExecutors = setup.toolExecutors();
        HitlDecision.HitlVerdict topVerdict = decision.getVerdict();
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions() != null ? decision.getToolDecisions() : Map.of();

        // Per-request execution controls (mirrors the live loop).
        boolean enableRateLimiting = task.getEnableRateLimiting() != null ? task.getEnableRateLimiting() : true;
        boolean enableCaching = task.getEnableToolCaching() != null ? task.getEnableToolCaching() : true;
        boolean enableCostTracking = task.getEnableCostTracking() != null ? task.getEnableCostTracking() : true;
        int defaultRateLimit = task.getDefaultRateLimit() != null ? task.getDefaultRateLimit() : 100;
        Map<String, Integer> toolRateLimits = task.getToolRateLimits();
        Map<String, String> toolCanonicalNames = setup.toolCanonicalNames();
        Double maxBudget = task.getMaxBudgetPerConversation();
        List<ToolSpecification> builtInSpecs = setup.builtInSpecs();

        for (PendingToolCallBatch.PendingToolCall c : batch.getCalls()) {
            ToolCallDecision cd = perCall.get(c.getCallId());
            HitlDecision.HitlVerdict verdict = cd != null && cd.getVerdict() != null ? cd.getVerdict() : topVerdict;
            String note = cd != null ? cd.getNote() : decision.getNote();
            String amended = cd != null ? cd.getAmendedArguments() : null;

            if (verdict == HitlDecision.HitlVerdict.REJECTED) {
                currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), rejectionEnvelope(c.getToolName(), note)));
                trace.add(Map.of("type", "hitl_rejected", "tool", c.getToolName(), "callId", c.getCallId()));
                continue;
            }

            // APPROVED (top-level default or per-call) below.

            // Truncated raw args can't be honestly executed (the approver never saw the
            // full args and the raw bytes are incomplete). Validation already blocks
            // amendments on these — approving yields an honest non-execution.
            if (c.isArgsTruncated()) {
                currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c),
                        "{\"status\":\"NOT_EXECUTED\",\"reason\":\"arguments exceeded the persistable size cap\"}"));
                trace.add(Map.of("type", "hitl_not_executed", "tool", c.getToolName(), "callId", c.getCallId()));
                continue;
            }

            // Journal protocol — at-most-once across crashes/re-approvals.
            if (journalStore.tryClaim(conversationId, pauseEpoch, c.getCallId(), c.getToolName(), decision.getDecidedBy())) {
                String args = amended != null ? amended : c.getArgumentsRaw();
                ToolExecutionRequest req = rebuiltRequest(c, args);
                // Full per-request pipeline (checkpoint, budget, executeToolWrapped,
                // truncation, trace). Its own auto-checkpoint fires ONLY here.
                String result = executeSingleToolCallResult(req, memory, trace, toolExecutors, toolRateLimits,
                        toolCanonicalNames, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                        enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
                journalStore.markExecuted(conversationId, pauseEpoch, c.getCallId(), capUtf8(result, JOURNAL_RESULT_MAX_BYTES));
                String envelope = amended != null ? amendedEnvelope(result) : result;
                currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), envelope));
                clearedCallIds.add(c.getCallId());
            } else {
                // Duplicate claim — a prior attempt already ran (or crashed mid-tool).
                var prior = journalStore.find(conversationId, pauseEpoch, c.getCallId());
                if (prior.isPresent() && prior.get().status() == IHitlToolJournalStore.Status.EXECUTED) {
                    // Replay the stored result — NEVER re-execute (no checkpoint re-fire).
                    currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c), prior.get().resultCapped()));
                    trace.add(Map.of("type", "hitl_replayed", "tool", c.getToolName(), "callId", c.getCallId()));
                    clearedCallIds.add(c.getCallId());
                } else {
                    // EXECUTING (crash inside the tool) — honest at-most-once outcome.
                    currentMessages.add(ToolExecutionResultMessage.from(rebuiltRequest(c),
                            "{\"status\":\"EXECUTION_OUTCOME_UNKNOWN\",\"message\":\"a previous execution attempt was interrupted; "
                                    + "it may or may not have taken effect — verify externally before retrying\"}"));
                    auditOutcomeUnknown(memory, c);
                    trace.add(Map.of("type", "hitl_outcome_unknown", "tool", c.getToolName(), "callId", c.getCallId()));
                    clearedCallIds.add(c.getCallId());
                }
            }
        }

        // ── Step 4: continue the SAME loop from the next iteration (budget-continuous)
        // ──
        // The gate resolves the SAME way the live path did (LlmTask.executeTask): the
        // cluster-wide eddi.hitl.tool.enabled kill-switch nulls the config (gate
        // inert), otherwise task override else agent default, so NEW calls in the
        // continuation re-gate → re-pause. Approved ids are pre-cleared so they are
        // never re-gated if the model reissues them. Threading toolHitlEnabled here
        // keeps the resume path from re-arming an approval flow an operator disabled.
        ToolApprovalsConfig effectiveToolApprovals = null;
        if (toolHitlEnabled) {
            effectiveToolApprovals = task.getToolApprovals() != null
                    ? task.getToolApprovals()
                    : memory.getAgentToolApprovalsConfig();
        }
        int llmTaskIndex = batch.getLlmTaskIndex();

        // Transcript cap: resumeToolLoop replays the ALREADY-serialized transcript
        // via ChatTranscriptCodec#deserialize (no cap involved) and never threads the
        // configured value in (LlmTask does not resolve it for the resume path). If
        // this continuation re-gates a fresh call and re-pauses, the resulting batch
        // is capped at the constant default here — a defensible, rare-path fallback
        // rather than widening resumeToolLoop's signature for the primary knob, which
        // governs the initial pause.
        TokenUsage[] tokenHolder = new TokenUsage[1];
        String response = runToolCallLoop(chatModel, currentMessages, activeSpecs, trace, batch.getIterationIndex() + 1,
                setup, isLazy, task, memory, effectiveToolApprovals, llmTaskIndex, clearedCallIds, DEFAULT_TRANSCRIPT_MAX_BYTES, tokenHolder,
                jsonPolicy);

        // ── Step 5: merge the pre-pause trace with the resume trace ──
        List<Map<String, Object>> mergedTrace = new ArrayList<>();
        if (batch.getTraceSoFar() != null) {
            mergedTrace.addAll(batch.getTraceSoFar());
        }
        mergedTrace.addAll(trace);

        Map<String, Object> responseMetadata = new HashMap<>();
        if (tokenHolder[0] != null) {
            responseMetadata.put("tokenUsage", tokenUsageMap(tokenHolder[0]));
        }
        responseMetadata.put("toolCostUsd", toolCostDelta(conversationId, toolCostBefore));
        return new ExecutionResult(response, mergedTrace, responseMetadata);
    }

    /** Journal-stored result cap (bytes) — matches the journal store's own cap. */
    private static final int JOURNAL_RESULT_MAX_BYTES = 32_768;

    /**
     * Restores the active-spec surface on resume. For EAGER, every registered spec.
     * For LAZY, exactly the specs that were active at pause time (by name), falling
     * back to the LAZY-initial surface when the recorded names are absent.
     */
    private static List<ToolSpecification> restoreActiveSpecs(ToolSetup setup, boolean isLazy, List<String> activatedToolNames) {
        if (!isLazy) {
            return setup.toolSpecs();
        }
        if (activatedToolNames == null || activatedToolNames.isEmpty()) {
            return computeInitialActiveSpecs(setup, true);
        }
        Set<String> names = new HashSet<>(activatedToolNames);
        List<ToolSpecification> restored = new ArrayList<>();
        for (ToolSpecification spec : setup.toolSpecs()) {
            if (names.contains(spec.name())) {
                restored.add(spec);
            }
        }
        return restored.isEmpty() ? computeInitialActiveSpecs(setup, true) : restored;
    }

    /**
     * Rebuilds the base history exactly as a fresh turn would (the ONE sanctioned
     * history rebuild on resume) and appends a reconstructed {@link AiMessage}
     * carrying the batch's gated calls, so the appended results bind by call id.
     * Intra-turn prior iterations are lost — accepted trade-off when the transcript
     * cannot be restored. Uses the task's own (untemplated) system/prompt params;
     * the orchestrator has no templating engine, and this degraded path only needs
     * to carry the gated AiMessage so the appended results bind by call id.
     */
    private List<ChatMessage> fallbackRebuildMessages(LlmConfiguration.Task task, IConversationMemory memory,
                                                      PendingToolCallBatch batch) {
        String systemMessage = "";
        String prompt = null;
        boolean includeFirstAgentMessage = true;
        int logSizeLimit = task.getConversationHistoryLimit() != null ? task.getConversationHistoryLimit() : -1;
        if (task.getParameters() != null) {
            Object sys = task.getParameters().get("systemMessage");
            if (sys != null) {
                systemMessage = sys.toString();
            }
            Object p = task.getParameters().get("prompt");
            if (p != null) {
                prompt = p.toString();
            }
            Object inc = task.getParameters().get("includeFirstAgentMessage");
            if (inc != null) {
                includeFirstAgentMessage = Boolean.parseBoolean(inc.toString());
            }
        }

        List<ChatMessage> base = conversationHistoryBuilder.buildMessages(memory, systemMessage, prompt, logSizeLimit,
                includeFirstAgentMessage, null, 0);

        List<ChatMessage> messages = new ArrayList<>(base);
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (PendingToolCallBatch.PendingToolCall c : batch.getCalls()) {
            requests.add(rebuiltRequest(c));
        }
        messages.add(AiMessage.from(requests));
        return messages;
    }

    /**
     * When the gate is active, assigns a stable synthetic id to any tool-call
     * request the provider emitted WITHOUT one, so the id is identical across the
     * frozen pause transcript, the pending batch, and the resume result messages. A
     * null-id request in the transcript paired with an invented id on resume breaks
     * providers that match tool results by {@code tool_call_id}; and the gate
     * itself only records a reason per non-null id. Returns the message unchanged
     * when the gate is inert (pre-HITL byte-identical) or no id is missing.
     */
    private static AiMessage normalizeToolCallIds(AiMessage aiMessage, ToolApprovalsConfig effectiveToolApprovals) {
        boolean gateActive = effectiveToolApprovals != null
                && effectiveToolApprovals.getRequireApproval() != null
                && !effectiveToolApprovals.getRequireApproval().isEmpty();
        if (!gateActive || !aiMessage.hasToolExecutionRequests()) {
            return aiMessage;
        }
        List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
        if (requests.stream().allMatch(r -> r.id() != null)) {
            return aiMessage;
        }
        List<ToolExecutionRequest> normalized = new ArrayList<>(requests.size());
        for (ToolExecutionRequest r : requests) {
            if (r.id() == null) {
                normalized.add(ToolExecutionRequest.builder()
                        .id("gen-" + UUID.randomUUID())
                        .name(r.name())
                        .arguments(r.arguments() != null ? r.arguments() : "")
                        .build());
            } else {
                normalized.add(r);
            }
        }
        String text = aiMessage.text();
        return text != null && !text.isBlank()
                ? AiMessage.from(text, normalized)
                : AiMessage.from(normalized);
    }

    /** Rebuilds a provider-safe request from a pending call (original raw args). */
    private static ToolExecutionRequest rebuiltRequest(PendingToolCallBatch.PendingToolCall c) {
        return rebuiltRequest(c, c.getArgumentsRaw());
    }

    /**
     * Rebuilds a request binding by the original call id with the given arguments.
     */
    private static ToolExecutionRequest rebuiltRequest(PendingToolCallBatch.PendingToolCall c, String args) {
        return ToolExecutionRequest.builder()
                .id(c.getCallId())
                .name(c.getToolName())
                .arguments(args != null ? args : "")
                .build();
    }

    /**
     * Builds the reviewer-rejection envelope with the tool name and free-text note
     * JSON-escaped via Jackson — NEVER string-concatenated with raw reviewer text.
     */
    private String rejectionEnvelope(String toolName, String note) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "REJECTED_BY_REVIEWER");
        envelope.put("tool", toolName);
        envelope.put("note", note != null ? note : "");
        envelope.put("instruction", "The reviewer declined this action. Do not retry this exact call; "
                + "explain the situation to the user and offer alternatives.");
        return toJson(envelope);
    }

    /**
     * Wraps an executed amended-args result so the model knows why it may differ.
     */
    private String amendedEnvelope(String result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "EXECUTED");
        envelope.put("argsAmendedByReviewer", true);
        envelope.put("result", result);
        return toJson(envelope);
    }

    /**
     * Reusable Jackson mapper for approver/model-facing envelopes (escapes text).
     */
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();

    /**
     * Jackson serialization for approver/model-facing JSON envelopes. All embedded
     * reviewer/tool text is escaped by Jackson — NEVER string-concatenated.
     */
    private static String toJson(Object value) {
        try {
            return ENVELOPE_MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Fall back to a minimal, safe envelope rather than propagating — the
            // resume must still complete. Effectively unreachable for the small maps
            // serialized here.
            LOGGER.warnf("HITL resume: JSON envelope serialization failed: %s", e.getMessage());
            return "{\"status\":\"ERROR\",\"reason\":\"could not serialize result envelope\"}";
        }
    }

    /**
     * Records an at-most-once outcome-unknown event. No lightweight
     * {@code hitl.tool.*} audit collector is reachable from this task (the
     * {@link ai.labs.eddi.engine.audit.model.AuditEntry} record is built by the
     * LifecycleManager per-task with HMAC context we do not have here), so —
     * exactly as the config-drift path does — this WARN-logs with a distinctive
     * marker that operators can alert on. Package-private + overridable so tests
     * can assert it fired.
     */
    void auditOutcomeUnknown(IConversationMemory memory, PendingToolCallBatch.PendingToolCall c) {
        LOGGER.warnf("hitl.tool.outcome_unknown: approved tool '%s' (callId '%s') for conversation '%s' had an interrupted prior execution; "
                + "outcome is unknown — verify externally before retrying.",
                sanitize(c.getToolName()), sanitize(c.getCallId()), sanitize(memory.getConversationId()));
    }

    /**
     * The tool discovery + registration result shared by the live path
     * ({@link #executeWithTools}) and the HITL resume path
     * ({@link #resumeToolLoop}). Holds the fully merged specs/executors/sources
     * plus the built-in-only specs (needed for LAZY activation).
     *
     * @param toolSpecs
     *            every registered tool spec (built-in + http + mcp + a2a)
     * @param toolExecutors
     *            dispatch name → executor for every registered tool
     * @param toolSources
     *            dispatch name → provenance tag for gate qualified matching
     * @param builtInSpecs
     *            built-in specs only (copy taken before external merge — LAZY needs
     *            it to activate discovered built-ins)
     * @param toolCanonicalNames
     *            dispatch name → configuration slug, for built-ins only. Lets the
     *            executor boundary price a call and pick its cache TTL under the
     *            token the agent designer actually configured
     *            ({@code searchWeb → websearch}); tools that are configured under
     *            their dispatch name (http/mcp/a2a) are simply absent
     */
    record ToolSetup(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> toolExecutors,
            Map<String, String> toolSources, List<ToolSpecification> builtInSpecs,
            Map<String, String> toolCanonicalNames) {
    }

    /**
     * Runs the same tool discovery + registration prologue for a task that the live
     * loop uses, so the live path and {@link #resumeToolLoop} share ONE copy.
     * Collects enabled built-in tools, discovers httpcall/mcpcall/a2a tools from
     * the workflow, builds specs + executors via reflection, and merges external
     * tools — returning the fully populated {@link ToolSetup}.
     */
    ToolSetup buildToolSetup(LlmConfiguration.Task task, IConversationMemory memory) {
        // Collect enabled built-in tools
        List<Object> tools = collectEnabledTools(task, memory);

        // Discover httpcall tools from workflow (auto-discovery)
        boolean enableHttpCallTools = task.getEnableHttpCallTools() == null || task.getEnableHttpCallTools();
        HttpCallToolsResult httpCallTools = enableHttpCallTools ? discoverHttpCallTools(memory) : null;

        // Discover mcpcalls tools from workflow (auto-discovery)
        boolean enableMcpCallTools = task.getEnableMcpCallTools() == null || task.getEnableMcpCallTools();
        McpToolProviderManager.McpToolsResult mcpCallWorkflowTools = enableMcpCallTools ? discoverMcpCallTools(memory) : null;

        // Discover A2A agent tools (if configured)
        A2AToolProviderManager.A2AToolsResult a2aTools = null;
        List<A2AAgentConfig> a2aAgents = task.getA2aAgents();
        if (a2aAgents != null && !a2aAgents.isEmpty()) {
            a2aTools = a2aToolProviderManager.discoverTools(a2aAgents);
        }

        // Build tool specifications and executors from built-in tool objects.
        // toolSources maps dispatch name → provenance ("builtin"/"http"/"mcp"/…) so
        // the gate can match qualified "source:name" patterns; missing entries are
        // tolerated (the gate falls back to bare-name matching).
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> toolExecutors = new HashMap<>();
        Map<String, String> toolSources = new HashMap<>();
        Map<String, String> toolCanonicalNames = new HashMap<>();

        for (Object tool : tools) {
            // CDI proxies don't carry @Tool annotations — resolve to actual bean class
            Class<?> toolClass = tool.getClass();
            if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
                toolClass = toolClass.getSuperclass();
            }

            // Resolved from the UNWRAPPED class on purpose: tool.getClass() would be
            // "CalculatorTool_ClientProxy" under CDI, which no resolver case matches, and
            // every built-in would silently fall back to its dispatch name again.
            String canonicalToolName = ToolNameResolver.canonicalForClass(toolClass.getSimpleName());

            var specs = ToolSpecifications.toolSpecificationsFrom(toolClass);
            toolSpecs.addAll(specs);

            // Find methods annotated with @Tool and map them to executors
            for (java.lang.reflect.Method method : toolClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                    String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                    toolExecutors.put(toolName, new DefaultToolExecutor(tool, method));
                    toolSources.put(toolName, sourceForBuiltInTool(tool));
                    toolCanonicalNames.put(toolName, canonicalToolName != null ? canonicalToolName : toolName);
                }
            }
        }

        // Copy built-in specs before merging external ones — LAZY activation needs it.
        List<ToolSpecification> builtInSpecs = new ArrayList<>(toolSpecs);

        // Merge httpcall tools discovered from workflow (if any)
        if (httpCallTools != null && !httpCallTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(httpCallTools.toolSpecs());
            toolExecutors.putAll(httpCallTools.executors());
            httpCallTools.executors().keySet().forEach(name -> toolSources.put(name, "http"));
        }

        // Merge mcpcalls tools discovered from workflow (if any)
        if (mcpCallWorkflowTools != null && !mcpCallWorkflowTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(mcpCallWorkflowTools.toolSpecs());
            toolExecutors.putAll(mcpCallWorkflowTools.executors());
            mcpCallWorkflowTools.executors().keySet().forEach(name -> toolSources.put(name, "mcp"));
        }

        // Merge A2A agent tools (if any)
        if (a2aTools != null && !a2aTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(a2aTools.toolSpecs());
            toolExecutors.putAll(a2aTools.executors());
            a2aTools.executors().keySet().forEach(name -> toolSources.put(name, "a2a"));
        }

        return new ToolSetup(toolSpecs, toolExecutors, toolSources, builtInSpecs, Map.copyOf(toolCanonicalNames));
    }

    /**
     * Computes the specs the LLM initially sees given a {@link ToolSetup}. For
     * EAGER, that is every registered spec. For LAZY, only {@code discover_tools}
     * plus the external (http/mcp/a2a) specs — the built-ins stay hidden until
     * discovery activates them. Shared by the live loop and resume so both present
     * an identical initial surface. Returns a fresh mutable list (LAZY activation
     * mutates it in place).
     */
    private static List<ToolSpecification> computeInitialActiveSpecs(ToolSetup setup, boolean isLazy) {
        if (!isLazy) {
            return setup.toolSpecs();
        }
        // External = every registered spec whose name is not a built-in spec name.
        Set<String> builtInNames = new HashSet<>();
        for (ToolSpecification spec : setup.builtInSpecs()) {
            builtInNames.add(spec.name());
        }
        List<ToolSpecification> activeSpecs = new ArrayList<>();
        int externalCount = 0;
        for (ToolSpecification spec : setup.toolSpecs()) {
            if ("discover_tools".equals(spec.name())) {
                activeSpecs.add(spec);
            } else if (!builtInNames.contains(spec.name())) {
                activeSpecs.add(spec);
                externalCount++;
            }
        }
        LOGGER.infof("LAZY mode: presenting %d specs initially (discover_tools + %d external)",
                activeSpecs.size(), externalCount);
        return activeSpecs;
    }

    /**
     * Executes the tool-calling loop using direct ChatModel API against a pre-built
     * {@link ToolSetup} (shared with the resume path).
     */
    private ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, ToolSetup setup,
                                             LlmConfiguration.Task task, IConversationMemory memory,
                                             ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex, int transcriptMaxBytes,
                                             JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {

        Map<String, ToolExecutor> toolExecutors = setup.toolExecutors();
        Map<String, String> toolSources = setup.toolSources();
        List<ToolSpecification> builtInSpecs = setup.builtInSpecs();

        boolean isLazy = task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY;

        // Active specs: what the LLM currently sees (LAZY starts narrow).
        List<ToolSpecification> activeSpecs = computeInitialActiveSpecs(setup, isLazy);

        // Build message list with system message if provided
        List<ChatMessage> messages = new ArrayList<>();
        if (!isNullOrEmpty(systemMessage)) {
            messages.add(SystemMessage.from(systemMessage));
        }
        messages.addAll(chatMessages);

        // Trace for tool calls
        List<Map<String, Object>> trace = new ArrayList<>();

        // Live path: iteration loop starts at 0, no pre-cleared call ids.
        String conversationId = memory != null ? memory.getConversationId() : null;
        double toolCostBefore = conversationToolCost(conversationId);
        TokenUsage[] tokenHolder = new TokenUsage[1];
        String response = runToolCallLoop(chatModel, messages, activeSpecs, trace, 0,
                setup, isLazy, task, memory, effectiveToolApprovals, llmTaskIndex, Set.of(), transcriptMaxBytes, tokenHolder, jsonPolicy);

        Map<String, Object> responseMetadata = new HashMap<>();
        if (tokenHolder[0] != null) {
            responseMetadata.put("tokenUsage", tokenUsageMap(tokenHolder[0]));
        }
        responseMetadata.put("toolCostUsd", toolCostDelta(conversationId, toolCostBefore));
        return new ExecutionResult(response, trace, responseMetadata);
    }

    /**
     * Accumulated tool cost for a conversation, or {@code 0.0} when nothing has
     * been tracked for it yet — {@link ToolCostTracker#getConversationCosts}
     * returns null for an untracked conversation.
     */
    private double conversationToolCost(String conversationId) {
        if (conversationId == null || toolExecutionService == null) {
            return 0.0;
        }
        ToolCostTracker tracker = toolExecutionService.getCostTracker();
        if (tracker == null) {
            return 0.0;
        }
        ToolCostTracker.ConversationCostMetrics metrics = tracker.getConversationCosts(conversationId);
        return metrics != null ? metrics.getTotalCost() : 0.0;
    }

    /**
     * Dollar cost the tools of THIS model call added, as the difference between two
     * snapshots of the conversation total. Clamped at zero because
     * {@link ToolCostTracker#resetConversation} can fire between the snapshots and
     * would otherwise yield a negative "cost".
     */
    private double toolCostDelta(String conversationId, double costBefore) {
        return Math.max(0.0, conversationToolCost(conversationId) - costBefore);
    }

    /**
     * The single shared tool-calling loop, wrapped in
     * {@link AgentExecutionHelper#executeWithRetry}. Used by the live path (start
     * iteration 0, empty {@code clearedCallIds}) and by {@link #resumeToolLoop}
     * (start iteration {@code batch.getIterationIndex()+1}, {@code clearedCallIds}
     * = the human-approved ids so they are never re-gated). A fresh gated batch
     * throws {@link ToolApprovalRequiredException} to re-pause — the retry guard
     * lets it escape unchanged.
     *
     * @param initialMessages
     *            the message list the loop starts from (defensively copied inside);
     *            for resume this already carries the replayed transcript + the
     *            verdict-applied tool results
     * @param startIteration
     *            first loop index — carries budget continuity across a pause
     * @param clearedCallIds
     *            call ids a human already approved this pause; never re-gated
     * @param transcriptMaxBytes
     *            the configured cap (bytes) for freezing the transcript into a
     *            {@link PendingToolCallBatch} if this iteration re-pauses
     * @param jsonPolicy
     *            decides, per request, whether {@code ResponseFormat.JSON} is set;
     *            resolved against whether THAT request carries tool specifications
     */
    private String runToolCallLoop(ChatModel chatModel, List<ChatMessage> initialMessages, List<ToolSpecification> activeSpecs,
                                   List<Map<String, Object>> trace, int startIteration, ToolSetup setup, boolean isLazy,
                                   LlmConfiguration.Task task, IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals,
                                   int llmTaskIndex, Set<String> clearedCallIds, int transcriptMaxBytes, TokenUsage[] tokenHolder,
                                   JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {

        Map<String, ToolExecutor> toolExecutors = setup.toolExecutors();
        Map<String, String> toolSources = setup.toolSources();
        Map<String, String> toolCanonicalNames = setup.toolCanonicalNames();
        List<ToolSpecification> builtInSpecs = setup.builtInSpecs();

        boolean enableRateLimiting = task.getEnableRateLimiting() != null ? task.getEnableRateLimiting() : true;
        boolean enableCaching = task.getEnableToolCaching() != null ? task.getEnableToolCaching() : true;
        boolean enableCostTracking = task.getEnableCostTracking() != null ? task.getEnableCostTracking() : true;
        int defaultRateLimit = task.getDefaultRateLimit() != null ? task.getDefaultRateLimit() : 100;
        Map<String, Integer> toolRateLimits = task.getToolRateLimits();
        Double maxBudget = task.getMaxBudgetPerConversation();
        String conversationId = memory.getConversationId();

        // In-turn tool-context ceiling. Resolved once per loop: <= 0 disables the
        // guard entirely (and skips estimator construction with it).
        int toolContextBudget = task.getMaxToolContextTokens() != null
                ? task.getMaxToolContextTokens()
                : DEFAULT_MAX_TOOL_CONTEXT_TOKENS;
        TokenCountEstimator toolContextEstimator = toolContextBudget > 0 ? resolveToolContextEstimator(task) : null;

        return AgentExecutionHelper.executeWithRetry(() -> {
            // A retry REPLAYS this whole lambda, so anything the previous attempt
            // accumulated must be discarded — otherwise a turn that retried once
            // reports (and bills) roughly double the tokens it actually used.
            tokenHolder[0] = null;
            List<ChatMessage> currentMessages = new ArrayList<>(initialMessages);
            // Per-message token memo, local to this attempt: messages are immutable and
            // re-counted on every iteration, so without it a 10-iteration loop tokenizes
            // the first tool result ten times. Lives on the call stack — the orchestrator
            // is a shared singleton and must stay stateless.
            Map<ChatMessage, Integer> toolContextTokenMemo = new IdentityHashMap<>();
            int maxIterations = task.getMaxToolIterations() != null ? task.getMaxToolIterations() : 10;

            // Engine-enforced counterweight: strict mode caps iterations
            var counterweight = task.getCounterweight();
            if (counterweight != null && counterweight.isEnabled()
                    && "strict".equalsIgnoreCase(counterweight.getLevel())) {
                int strictCap = 5;
                if (maxIterations > strictCap) {
                    LOGGER.infof("Counterweight strict mode: capping tool iterations from %d to %d",
                            maxIterations, strictCap);
                    maxIterations = strictCap;
                }
            }

            for (int i = startIteration; i < maxIterations; i++) {
                // Cooperative cancellation: if this step was interrupted (e.g. a cascade
                // per-step timeout called future.cancel(true)), stop before issuing another
                // model call — avoids launching further side-effectful tools on a step whose
                // result will be discarded. Thread.interrupted() also CLEARS the flag so it
                // cannot leak to any later work on this thread.
                if (Thread.interrupted()) {
                    throw new LifecycleException("Agent execution cancelled (interrupted)");
                }

                // Keep the accumulated tool traffic inside its aggregate ceiling BEFORE
                // the request is built — this is the only point at which the loop knows
                // everything it is about to send. Without it the list only ever grows and
                // a tool-heavy turn hard-fails mid-loop on a provider context-window 400.
                if (toolContextEstimator != null) {
                    enforceToolContextBudget(currentMessages, toolContextBudget, toolContextEstimator,
                            toolContextTokenMemo, trace, conversationId);
                }

                ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(currentMessages);

                boolean toolsInRequest = !activeSpecs.isEmpty();
                if (toolsInRequest) {
                    requestBuilder.toolSpecifications(activeSpecs);
                }

                // API-level JSON, decided against THIS request's tool surface. Baking it
                // into the model instead would put it on every request the cached model
                // ever serves — the Gemini 400 documented in docs/changelog.md.
                if (jsonPolicy != null) {
                    var responseFormat = jsonPolicy.resolve(toolsInRequest);
                    if (responseFormat != null) {
                        requestBuilder.responseFormat(responseFormat);
                    }
                }

                ChatRequest chatRequest = requestBuilder.build();

                ChatResponse chatResponse = chatModel.chat(chatRequest);
                AiMessage aiMessage = normalizeToolCallIds(chatResponse.aiMessage(), effectiveToolApprovals);
                currentMessages.add(aiMessage);

                // Accumulate token usage for cost/observability reporting.
                if (chatResponse.metadata() != null && chatResponse.metadata().tokenUsage() != null) {
                    tokenHolder[0] = sumTokens(tokenHolder[0], chatResponse.metadata().tokenUsage());
                }

                if (aiMessage.hasToolExecutionRequests()) {
                    // === Tool-approval gate (tool-level HITL) ===
                    // Split the batch into gated (require human approval) and allowed
                    // calls. clearedCallIds carries the human-approved ids on resume so
                    // they are never re-gated. Inert when effectiveToolApprovals is
                    // null/empty — byte-identical to the pre-HITL path.
                    var gateResult = toolApprovalGate.classify(aiMessage.toolExecutionRequests(), toolSources,
                            effectiveToolApprovals, clearedCallIds);

                    if (!gateResult.gated().isEmpty()) {
                        int pausesSoFar = readToolPauseCount(memory);
                        if (pausesSoFar >= maxPausesPerTurn(effectiveToolApprovals)) {
                            // Fail-closed: the pause budget for this turn is spent —
                            // do not pause and do not execute the gated calls; tell
                            // the model to stop asking so the loop can still finish
                            // with the ungated results.
                            for (ToolExecutionRequest gatedReq : gateResult.gated()) {
                                currentMessages.add(ToolExecutionResultMessage.from(gatedReq,
                                        "{\"status\":\"DENIED\",\"reason\":\"approval-pause limit for this turn reached; do not retry\"}"));
                                Map<String, Object> capStep = new HashMap<>();
                                capStep.put("type", "tool_error");
                                capStep.put("tool", gatedReq.name());
                                capStep.put("error", "hitl_pause_cap");
                                trace.add(capStep);
                            }
                            // Task 10 — the pause-cap guard fired: record it as a guard
                            // (metric + audit) HERE where the audit context lives (the
                            // memory's audit collector, set by ConversationService on the
                            // say/resume paths). AgentOrchestrator is not CDI and has no
                            // MeterRegistry, so the metric uses the Micrometer global
                            // registry (same idiom as LifecycleManager). Emitted once per
                            // capped batch, carrying the gated fingerprint.
                            recordPauseCapGuard(memory, fingerprint(gateResult.gated()));
                            // ungated calls still execute below
                        } else {
                            // 1) execute the ungated calls of this batch normally
                            for (ToolExecutionRequest allowedReq : gateResult.allowed()) {
                                executeSingleToolCall(allowedReq, memory, currentMessages, trace, toolExecutors,
                                        toolRateLimits, toolCanonicalNames, defaultRateLimit, maxBudget, conversationId,
                                        enableRateLimiting, enableCaching, enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
                            }
                            // Abandoned-thread guard: a cascade step that timed out (or
                            // the agentTimeout watchdog on the live path) cancels the future
                            // via cancel(true), interrupting THIS thread — but it keeps
                            // running to here on the shared live memory while the caller has
                            // already moved on. Committing pause state now would leave a
                            // stale batch on a conversation the caller abandoned (self-heals
                            // at next turn start) or overwrite a later step's real pause.
                            // Abort WITHOUT mutating shared memory: throw the interrupted
                            // signal instead of the pause. NOT ToolApprovalRequiredException,
                            // which would commit a pause with a null batch. The guarantee
                            // here is only that this abandoned thread never writes stale/
                            // overwriting pause state; the resulting terminal state is
                            // fail-safe either way — a cascade discards the Future's exception
                            // entirely, and on the live path AgentExecutionHelper.executeWithRetry
                            // re-wraps this into a plain LifecycleException so the turn settles
                            // to ERROR (where the watchdog fired it already persisted
                            // EXECUTION_INTERRUPTED). Both are recoverable by the next say.
                            if (Thread.currentThread().isInterrupted()) {
                                throw new LifecycleException.LifecycleInterruptedException(
                                        "Tool-approval pause abandoned: executing thread was interrupted before commit");
                            }
                            // 2) snapshot + persist the pending batch, then abort the loop
                            PendingToolCallBatch batch = buildPendingBatch(currentMessages, gateResult, task, memory,
                                    i, activatedToolNames(isLazy, activeSpecs), trace, pausesSoFar + 1, llmTaskIndex,
                                    toolSources, effectiveToolApprovals, transcriptMaxBytes);
                            memory.setHitlPendingToolCalls(batch);
                            incrementToolPauseCount(memory, pausesSoFar);
                            throw new ToolApprovalRequiredException(buildPauseReason(effectiveToolApprovals, gateResult), batch);
                        }
                    }

                    // Execute the allowed calls (was: aiMessage.toolExecutionRequests()).
                    // When the gate is inert, gateResult.allowed() == the full batch.
                    for (ToolExecutionRequest toolRequest : gateResult.allowed()) {
                        // Cooperative cancellation before each (potentially side-effectful) tool.
                        // Thread.interrupted() clears the flag so it cannot leak to later work.
                        if (Thread.interrupted()) {
                            throw new LifecycleException("Agent execution cancelled (interrupted) before tool: " + toolRequest.name());
                        }

                        executeSingleToolCall(toolRequest, memory, currentMessages, trace, toolExecutors,
                                toolRateLimits, toolCanonicalNames, defaultRateLimit, maxBudget, conversationId,
                                enableRateLimiting, enableCaching, enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
                    }
                } else {
                    return aiMessage.text();
                }
            }

            // Loop exhausted its iteration budget. The last message is usually the
            // model's final AiMessage; on resume with a spent budget it may instead be
            // a verdict-applied tool result — guard the cast either way.
            ChatMessage last = currentMessages.get(currentMessages.size() - 1);
            if (last instanceof AiMessage aiLast) {
                return aiLast.text() != null ? aiLast.text() : "Max tool iterations reached";
            }
            return "Max tool iterations reached";
        }, task, "Agent execution");
    }

    // ─── In-turn tool-context budget (D6b) ───

    /**
     * Resolves the estimator used to meter the in-turn tool context, reusing
     * {@link TokenCounterFactory} so the tool half of a request is counted by the
     * same rule as the history half.
     * <p>
     * The task's declared type/model may still carry unresolved global-variable
     * references at this point ({@code LlmTask} resolves them for the model lookup,
     * not for the task POJO), and an unknown model name can make a provider
     * tokenizer refuse to construct. Both outcomes fall back to the approximate
     * chars/4 estimator rather than propagating: a safety ceiling that throws is
     * worse than a slightly imprecise one.
     */
    private TokenCountEstimator resolveToolContextEstimator(LlmConfiguration.Task task) {
        try {
            Map<String, String> parameters = task.getParameters();
            String modelName = parameters != null ? LlmTask.resolveModelName(parameters) : null;
            return tokenCounterFactory.getEstimator(task.getType(), modelName);
        } catch (Exception e) {
            LOGGER.debugf("Tool-context budget: falling back to the approximate token estimator (%s)", e.getMessage());
            return tokenCounterFactory.getEstimator(null, null);
        }
    }

    /**
     * Drops the oldest complete tool exchanges from {@code messages} until the
     * accumulated in-turn tool context fits inside {@code budgetTokens}.
     *
     * <p>
     * A <em>tool exchange</em> is an {@link AiMessage} carrying tool-execution
     * requests plus the run of {@link ToolExecutionResultMessage}s that answers it.
     * Eviction operates on whole exchanges and nothing else, which is the entire
     * point: dropping a result without its requesting {@code AiMessage} leaves an
     * unanswerable {@code tool_call_id}, and dropping the {@code AiMessage} without
     * its results leaves a tool call the provider will reject — the eviction would
     * then <em>cause</em> the 400 it exists to prevent. System, user and
     * assistant-prose messages are never candidates; conversation history is
     * governed by {@code maxContextTokens}/{@code conversationHistoryLimit}, not by
     * this guard.
     * </p>
     *
     * <p>
     * The most recent exchange is never evicted. When it alone exceeds the budget
     * there is nothing eviction can do — the model asked for those results and must
     * see them — so the method logs the overrun and lets the request through
     * unchanged, exactly as today. That case is what {@code toolResponseLimits} is
     * for.
     * </p>
     *
     * <p>
     * Eviction is silent to the model on purpose: no gap-marker message is
     * injected. A {@code SystemMessage} mid-transcript is not portable across the
     * twelve supported providers (several hoist system content to a dedicated
     * top-level field), and a {@code UserMessage} would fabricate a turn the user
     * never took. The loss is instead reported to the agent designer through the
     * execution trace, a Micrometer counter and a WARN log.
     * </p>
     *
     * <p>
     * Static and fully parameterized — the orchestrator is an application-scoped
     * singleton and holds no per-conversation state.
     * </p>
     *
     * @param messages
     *            the live message list; mutated in place
     * @param budgetTokens
     *            the aggregate ceiling; callers skip this method entirely when it
     *            is not positive
     * @param tokenMemo
     *            per-attempt cache of message → token count
     * @param trace
     *            execution trace; receives one {@code tool_context_evicted} entry
     *            per eviction round
     */
    static void enforceToolContextBudget(List<ChatMessage> messages, int budgetTokens, TokenCountEstimator estimator,
                                         Map<ChatMessage, Integer> tokenMemo, List<Map<String, Object>> trace,
                                         String conversationId) {

        List<int[]> exchanges = findToolExchanges(messages);
        // Nothing to trade away: with one exchange (or none) the only candidate is the
        // one the model is waiting on.
        if (exchanges.size() < 2) {
            return;
        }

        int total = 0;
        for (int[] range : exchanges) {
            for (int i = range[0]; i <= range[1]; i++) {
                total += tokensOf(messages.get(i), estimator, tokenMemo);
            }
        }
        if (total <= budgetTokens) {
            return;
        }

        int tokensBefore = total;
        int evictedExchanges = 0;
        int evictedMessages = 0;
        while (total > budgetTokens && evictedExchanges < exchanges.size() - 1) {
            int[] range = exchanges.get(evictedExchanges);
            for (int i = range[0]; i <= range[1]; i++) {
                total -= tokensOf(messages.get(i), estimator, tokenMemo);
            }
            evictedMessages += range[1] - range[0] + 1;
            evictedExchanges++;
        }

        // Remove highest index first so the earlier ranges stay valid. Ranges are
        // removed individually rather than as one span so that anything sitting
        // between two exchanges is left untouched.
        for (int k = evictedExchanges - 1; k >= 0; k--) {
            int[] range = exchanges.get(k);
            messages.subList(range[0], range[1] + 1).clear();
        }

        boolean withinBudget = total <= budgetTokens;

        Map<String, Object> evictionStep = new HashMap<>();
        evictionStep.put("type", "tool_context_evicted");
        evictionStep.put("evictedExchanges", evictedExchanges);
        evictionStep.put("evictedMessages", evictedMessages);
        evictionStep.put("tokensBefore", tokensBefore);
        evictionStep.put("tokensAfter", total);
        evictionStep.put("budgetTokens", budgetTokens);
        evictionStep.put("withinBudget", withinBudget);
        trace.add(evictionStep);

        try {
            Metrics.globalRegistry.counter("eddi.llm.tool_context.evictions",
                    "outcome", withinBudget ? "within_budget" : "still_over_budget").increment(evictedExchanges);
        } catch (Exception e) {
            LOGGER.debugf("tool-context eviction metric emit failed: %s", e.getMessage());
        }

        LOGGER.warnf("llm.tool_context.evicted: conversation '%s' accumulated %d tokens of tool context against "
                + "maxToolContextTokens=%d; dropped the %d oldest tool exchange(s) (%d messages), now %d tokens.%s "
                + "The model can no longer see those tool results — lower maxToolIterations, set toolResponseLimits, "
                + "or raise maxToolContextTokens.",
                sanitize(conversationId), tokensBefore, budgetTokens, evictedExchanges, evictedMessages, total,
                withinBudget ? "" : " STILL OVER BUDGET: the most recent exchange alone exceeds the ceiling and is never evicted.");
    }

    /**
     * Locates every tool exchange in message order. Each entry is an inclusive
     * {@code [start, end]} index pair whose {@code start} is an {@link AiMessage}
     * with tool-execution requests and whose {@code end} is the last
     * {@link ToolExecutionResultMessage} immediately following it.
     */
    private static List<int[]> findToolExchanges(List<ChatMessage> messages) {
        List<int[]> exchanges = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                int end = i;
                while (end + 1 < messages.size() && messages.get(end + 1) instanceof ToolExecutionResultMessage) {
                    end++;
                }
                exchanges.add(new int[]{i, end});
                i = end;
            }
        }
        return exchanges;
    }

    /** Memoized token count for one message, falling back to chars/4 on failure. */
    private static int tokensOf(ChatMessage message, TokenCountEstimator estimator, Map<ChatMessage, Integer> memo) {
        Integer cached = memo.get(message);
        if (cached != null) {
            return cached;
        }
        String text = TokenCounterFactory.extractText(message);
        int tokens;
        try {
            tokens = estimator.estimateTokenCountInText(text);
        } catch (Exception e) {
            // A provider tokenizer that rejects a payload must not abort the turn; an
            // approximate count still keeps the ceiling meaningful.
            tokens = text.length() / 4;
        }
        memo.put(message, tokens);
        return tokens;
    }

    /**
     * Sum two (possibly null) TokenUsage values field-by-field, tolerating nulls.
     * Package-private for direct unit testing of the accumulation arithmetic.
     */
    static TokenUsage sumTokens(TokenUsage a, TokenUsage b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new TokenUsage(sumInt(a.inputTokenCount(), b.inputTokenCount()), sumInt(a.outputTokenCount(), b.outputTokenCount()),
                sumInt(a.totalTokenCount(), b.totalTokenCount()));
    }

    static Integer sumInt(Integer a, Integer b) {
        return (a != null ? a : 0) + (b != null ? b : 0);
    }

    /**
     * The three token-usage count keys of the map {@link #tokenUsageMap} produces,
     * in reporting order. Shared by every consumer that merges usage maps.
     */
    static final List<String> TOKEN_USAGE_FIELDS = List.of("inputTokens", "outputTokens", "totalTokens");

    /**
     * Convert a TokenUsage into a template/audit-friendly map with non-null counts.
     */
    static Map<String, Object> tokenUsageMap(TokenUsage usage) {
        Map<String, Object> map = new HashMap<>();
        map.put("inputTokens", usage.inputTokenCount() != null ? usage.inputTokenCount() : 0);
        map.put("outputTokens", usage.outputTokenCount() != null ? usage.outputTokenCount() : 0);
        map.put("totalTokens", usage.totalTokenCount() != null ? usage.totalTokenCount() : 0);
        return map;
    }

    /**
     * Executes a single tool call through the full per-request pipeline:
     * auto-checkpoint, trace entry, per-conversation budget check, tenant cost
     * budget check, executor dispatch (via {@link ToolExecutionService} for rate
     * limiting/caching/cost tracking), response truncation, result trace, and LAZY
     * activation. Extracted verbatim from the live loop so the live path and Task
     * 9's resume path share ONE copy.
     * <p>
     * Package-private for direct unit testing of the gate.
     */
    void executeSingleToolCall(ToolExecutionRequest toolRequest, IConversationMemory memory,
                               List<ChatMessage> currentMessages, List<Map<String, Object>> trace,
                               Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                               Map<String, String> toolCanonicalNames,
                               int defaultRateLimit, Double maxBudget, String conversationId,
                               boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                               LlmConfiguration.Task task, boolean isLazy,
                               List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
        // Live path: run the full pipeline, then append the raw result verbatim.
        String toolResult = executeSingleToolCallResult(toolRequest, memory, trace, toolExecutors, toolRateLimits,
                toolCanonicalNames, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
        currentMessages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
    }

    /**
     * Runs the full per-request pipeline (auto-checkpoint, trace entry,
     * per-conversation budget check, tenant cost budget check, executor dispatch
     * via {@link ToolExecutionService}, response truncation, result trace, LAZY
     * activation) and RETURNS the resulting tool-result string WITHOUT appending it
     * to any message list. The live loop appends the raw result; the resume path
     * needs the string to journal it and to build the amended-args envelope, so it
     * appends its own (possibly-enveloped) message. This is the single shared
     * per-request pipeline for both paths — the auto-checkpoint fires here (and
     * only here) so replayed/outcome-unknown resume calls never re-checkpoint.
     */
    String executeSingleToolCallResult(ToolExecutionRequest toolRequest, IConversationMemory memory,
                                       List<Map<String, Object>> trace,
                                       Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                                       Map<String, String> toolCanonicalNames,
                                       int defaultRateLimit, Double maxBudget, String conversationId,
                                       boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                                       LlmConfiguration.Task task, boolean isLazy,
                                       List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
        // Auto-checkpoint before tool execution (Wave 4)
        if (memorySnapshotService != null) {
            try {
                memorySnapshotService.createCheckpoint(
                        memory, "before_tool:" + toolRequest.name(),
                        AgentOrchestrator.class.getSimpleName());
            } catch (Exception cpEx) {
                LOGGER.warnf("Auto-checkpoint failed before tool '%s': %s",
                        toolRequest.name(), cpEx.getMessage());
            }
        }

        Map<String, Object> callStep = new HashMap<>();
        callStep.put("type", "tool_call");
        callStep.put("tool", toolRequest.name());
        callStep.put("arguments", toolRequest.arguments());
        trace.add(callStep);

        // Check per-conversation TOOL budget before executing tool.
        //
        // A configured maxBudgetPerConversation is enforced unless the operator says
        // otherwise: enforceBudget on the task wins, and BUDGET_ENFORCE_DEFAULT (true
        // unless eddi.tools.budget.enforce-by-default says so) decides when the task
        // is silent. Defaulting to "unenforced" would void the ceilings that http,
        // MCP, A2A and dynamic tools were already being refused by. Cost tracking
        // itself is unaffected by the flag.
        boolean enforceBudget = task.getEnforceBudget() != null ? task.getEnforceBudget() : BUDGET_ENFORCE_DEFAULT;
        if (enforceBudget && maxBudget != null && conversationId != null
                && !toolExecutionService.getCostTracker().isWithinBudget(conversationId, maxBudget)) {
            String budgetError = "Budget exceeded for conversation " + conversationId;
            LOGGER.warn(sanitize(budgetError));

            Map<String, Object> budgetStep = new HashMap<>();
            budgetStep.put("type", "tool_error");
            budgetStep.put("tool", toolRequest.name());
            budgetStep.put("error", budgetError);
            trace.add(budgetStep);

            return "Error: " + budgetError;
        }

        // Check tenant-level monthly cost budget (MCP governance)
        if (tenantQuotaService != null) {
            var costCheck = tenantQuotaService.checkCostBudget(tenantQuotaService.getDefaultTenantId());
            if (!costCheck.allowed()) {
                LOGGER.warnf("Tenant cost budget exceeded during tool call: %s", costCheck.reason());

                Map<String, Object> quotaStep = new HashMap<>();
                quotaStep.put("type", "tool_error");
                quotaStep.put("tool", toolRequest.name());
                quotaStep.put("error", costCheck.reason());
                trace.add(quotaStep);

                return "Error: " + costCheck.reason();
            }
        }

        // Execute through ToolExecutionService for rate limiting, caching, cost
        // tracking
        ToolExecutor executor = toolExecutors.get(toolRequest.name());
        String toolResult;
        if (executor != null) {
            // A built-in is DISPATCHED under its @Tool method name ("calculate") but
            // CONFIGURED under its whitelist slug ("calculator"). Resolve the slug once,
            // here, and hand both names down: only the price and the cache TTL are looked
            // up by slug, everything that identifies the individual call is not.
            String canonicalName = ToolNameResolver.canonical(toolRequest.name(), toolCanonicalNames);
            int rateLimit = resolveRateLimit(toolRateLimits, toolRequest.name(), canonicalName, defaultRateLimit);
            Double priceOverride = resolveOverride(task.getToolPricing(), toolRequest.name(), canonicalName);

            // Partition the tool-result cache by identity, so one user's result can never
            // be served back to another. A null tag means no usable identity was
            // available; ToolExecutionService then bypasses the cache entirely. Both
            // names go in: toolCacheScopes shares its key vocabulary with toolRateLimits
            // and toolPricing, so a slug-keyed narrowing override has to bind here too.
            String cacheScopeTag = ToolCacheService.resolveScopeTag(toolRequest.name(), canonicalName, task.getToolCacheScopes(),
                    task.getDefaultToolCacheScope(), memory != null ? memory.getUserId() : null, conversationId);

            var invocation = new ToolInvocation(toolRequest.name(), canonicalName, priceOverride);
            toolResult = toolExecutionService.executeToolWrapped(invocation, toolRequest.arguments(), cacheScopeTag, conversationId,
                    () -> executor.execute(toolRequest, null), enableRateLimiting, enableCaching, enableCostTracking, rateLimit);
        } else {
            toolResult = "Error: Tool '" + toolRequest.name() + "' not found";
        }

        // Apply response truncation (MCP governance)
        toolResult = toolResponseTruncator.truncateIfNeeded(
                toolRequest.name(), toolResult, task.getToolResponseLimits(),
                task.getType(), task.getParameters());

        Map<String, Object> resultStep = new HashMap<>();
        resultStep.put("type", "tool_result");
        resultStep.put("tool", toolRequest.name());
        resultStep.put("result", toolResult);
        trace.add(resultStep);

        // LAZY mode: after discover_tools returns, activate the matching built-in specs
        if (isLazy && "discover_tools".equals(toolRequest.name())) {
            activateDiscoveredTools(toolResult, builtInSpecs, activeSpecs);
        }

        return toolResult;
    }

    /**
     * Resolves the per-minute rate limit for one call: an entry keyed on the
     * dispatch name wins, then the canonical slug, then the task default.
     *
     * <p>
     * Dispatch-name-first keeps a config that pins a single method
     * ({@code {"searchNews": 5}}) more specific than one that covers the whole tool
     * ({@code {"websearch": 30}}), and is what makes the documented slug form bind
     * at all — a built-in's dispatch name never equals its slug.
     * </p>
     *
     * <p>
     * Note that only the LIMIT is slug-resolved; the bucket itself lives in
     * {@code ToolRateLimiter} under the dispatch name. {@code {"websearch": 30}}
     * therefore grants {@code searchWeb}, {@code searchNews} and
     * {@code searchWikipedia} 30 calls/min <em>each</em>, not 30 between them.
     * </p>
     */
    static int resolveRateLimit(Map<String, Integer> toolRateLimits, String dispatchName, String canonicalName,
                                int defaultRateLimit) {
        if (toolRateLimits == null) {
            return defaultRateLimit;
        }
        Integer limit = toolRateLimits.get(dispatchName);
        if (limit == null) {
            limit = toolRateLimits.get(canonicalName);
        }
        return limit != null ? limit : defaultRateLimit;
    }

    /**
     * Resolves an operator price override for one call, dispatch name before
     * canonical slug (same precedence as {@link #resolveRateLimit}). Returns
     * {@code null} when no override applies, leaving the default price table in
     * charge.
     */
    static Double resolveOverride(Map<String, Double> toolPricing, String dispatchName, String canonicalName) {
        if (toolPricing == null) {
            return null;
        }
        Double price = toolPricing.get(dispatchName);
        return price != null ? price : toolPricing.get(canonicalName);
    }

    // ─── Tool-approval gate helpers ───

    /** Maps a built-in tool instance to its gate source tag. */
    private static String sourceForBuiltInTool(Object tool) {
        Class<?> c = tool.getClass();
        if (c.getName().contains("_ClientProxy") || c.getName().contains("$$")) {
            c = c.getSuperclass();
        }
        String simple = c.getSimpleName();
        return switch (simple) {
            case "UserMemoryTool" -> "memory";
            case "ConversationRecallTool" -> "recall";
            case "CreateSubAgentTool", "ConverseWithAgentTool", "FindAgentsByCapabilityTool", "TeardownAgentTool" -> "dynamic";
            default -> "builtin";
        };
    }

    /** Reads this turn's cumulative gated-pause count (0 when absent). */
    private static int readToolPauseCount(IConversationMemory memory) {
        var step = memory.getCurrentStep();
        if (step == null) {
            return 0;
        }
        IData<Integer> data = step.getLatestData(KEY_TOOL_PAUSE_COUNT);
        if (data == null || data.getResult() == null) {
            return 0;
        }
        return data.getResult();
    }

    /** Writes the incremented gated-pause count for this turn. */
    private static void incrementToolPauseCount(IConversationMemory memory, int pausesSoFar) {
        var step = memory.getCurrentStep();
        if (step != null) {
            step.storeData(new Data<>(KEY_TOOL_PAUSE_COUNT, pausesSoFar + 1));
        }
    }

    /** Effective max pauses per turn (default 3, clamped 1..10). */
    private static int maxPausesPerTurn(ToolApprovalsConfig cfg) {
        if (cfg == null || cfg.getMaxPausesPerTurn() == null) {
            return DEFAULT_MAX_PAUSES_PER_TURN;
        }
        return Math.max(1, Math.min(10, cfg.getMaxPausesPerTurn()));
    }

    /** Names activated in LAZY mode (for resume reactivation); empty otherwise. */
    private static List<String> activatedToolNames(boolean isLazy, List<ToolSpecification> activeSpecs) {
        if (!isLazy) {
            return List.of();
        }
        return activeSpecs.stream().map(ToolSpecification::name).toList();
    }

    /**
     * Builds the approver-facing pause reason: {@code cfg.pauseReason} with
     * {@code {toolNames}} replaced by the comma-joined gated names, defaulting to
     * "Tool call requires approval: names". Redacted and capped at 500 chars.
     */
    private static String buildPauseReason(ToolApprovalsConfig cfg, ToolApprovalGate.GateResult gateResult) {
        String names = gateResult.gated().stream().map(ToolExecutionRequest::name).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("");
        String template = cfg != null && cfg.getPauseReason() != null && !cfg.getPauseReason().isBlank()
                ? cfg.getPauseReason()
                : "Tool call requires approval: {toolNames}";
        String reason = template.replace("{toolNames}", names);
        reason = SecretRedactionFilter.redact(reason);
        if (reason.length() > PAUSE_REASON_MAX_CHARS) {
            reason = reason.substring(0, PAUSE_REASON_MAX_CHARS);
        }
        return reason;
    }

    /**
     * Backward-compatible overload: transcript cap defaults to
     * {@link #DEFAULT_TRANSCRIPT_MAX_BYTES}.
     */
    PendingToolCallBatch buildPendingBatch(List<ChatMessage> currentMessages, ToolApprovalGate.GateResult gateResult,
                                           LlmConfiguration.Task task, IConversationMemory memory, int iterationIndex,
                                           List<String> activatedToolNames, List<Map<String, Object>> trace,
                                           int pauseCountThisTurn, int llmTaskIndex,
                                           Map<String, String> toolSources, ToolApprovalsConfig effectiveToolApprovals) {
        return buildPendingBatch(currentMessages, gateResult, task, memory, iterationIndex, activatedToolNames, trace,
                pauseCountThisTurn, llmTaskIndex, toolSources, effectiveToolApprovals, DEFAULT_TRANSCRIPT_MAX_BYTES);
    }

    /**
     * Snapshots the interrupted tool-call batch into a durable
     * {@link PendingToolCallBatch}. All approver-facing strings are secret-redacted
     * and size-capped.
     *
     * @param transcriptMaxBytes
     *            the configured cap (bytes) for serializing {@code currentMessages}
     *            — resolved by {@code LlmTask} from
     *            {@code eddi.hitl.tool.transcript-max-bytes} (default
     *            {@link PendingToolCallBatch#TRANSCRIPT_MAX_BYTES_DEFAULT}).
     */
    PendingToolCallBatch buildPendingBatch(List<ChatMessage> currentMessages, ToolApprovalGate.GateResult gateResult,
                                           LlmConfiguration.Task task, IConversationMemory memory, int iterationIndex,
                                           List<String> activatedToolNames, List<Map<String, Object>> trace,
                                           int pauseCountThisTurn, int llmTaskIndex,
                                           Map<String, String> toolSources, ToolApprovalsConfig effectiveToolApprovals,
                                           int transcriptMaxBytes) {
        PendingToolCallBatch batch = new PendingToolCallBatch();
        batch.setPauseEpoch(UUID.randomUUID().toString());
        batch.setLlmTaskId(task.getId());
        batch.setLlmTaskIndex(llmTaskIndex);
        // Fix #1: persist the EXACT effective tool-approval config that gated this
        // batch (task-level override when the task set one, else the agent-level
        // default) so the post-pause resolvers in ConversationService and
        // Conversation.resolvePendingMessage read the task-scoped config that produced
        // the pause instead of re-deriving from the agent level only.
        batch.setEffectiveToolApprovals(effectiveToolApprovals);
        // workflowId is informational; the authoritative pause bookmark carries the
        // paused workflow id (set by the LifecycleManager → Conversation pause commit).
        batch.setIterationIndex(iterationIndex);
        batch.setActivatedToolNames(activatedToolNames);
        batch.setPauseCountThisTurn(pauseCountThisTurn);
        batch.setAutoApproveCount(0);

        // Serialize the transcript (capped); omitted flag drives fallback on resume.
        ChatTranscriptCodec.CodecResult codecResult = chatTranscriptCodec.serialize(currentMessages, transcriptMaxBytes);
        batch.setChatTranscriptJson(codecResult.json());
        batch.setTranscriptOmitted(codecResult.omitted());

        // Per gated call: cap raw args, redact + cap redacted args, carry gate reason.
        List<PendingToolCallBatch.PendingToolCall> calls = new ArrayList<>();
        for (ToolExecutionRequest req : gateResult.gated()) {
            var call = new PendingToolCallBatch.PendingToolCall();
            String callId = req.id() != null ? req.id() : "gen-" + UUID.randomUUID();
            call.setCallId(callId);
            call.setToolName(req.name());
            String source = toolSources.getOrDefault(req.name(), "unknown");
            call.setSource(source);

            String rawArgs = req.arguments() != null ? req.arguments() : "";
            byte[] rawBytes = rawArgs.getBytes(StandardCharsets.UTF_8);
            if (rawBytes.length > PendingToolCallBatch.ARGS_RAW_MAX_BYTES) {
                call.setArgumentsRaw(capUtf8(rawArgs, PendingToolCallBatch.ARGS_RAW_MAX_BYTES));
                call.setArgsTruncated(true);
            } else {
                call.setArgumentsRaw(rawArgs);
                call.setArgsTruncated(false);
            }

            String redacted = SecretRedactionFilter.redact(rawArgs);
            call.setArgumentsRedacted(capUtf8(redacted, PendingToolCallBatch.ARGS_REDACTED_MAX_BYTES));

            // gateReason: the matched pattern (by call id), fall back to bare name.
            String reason = req.id() != null ? gateResult.gateReasonByCallId().get(req.id()) : null;
            call.setGateReason(reason);
            calls.add(call);
        }
        batch.setCalls(calls);

        // Ungated calls of this batch that already executed (approver visibility).
        batch.setExecutedUngatedCallNames(gateResult.allowed().stream().map(ToolExecutionRequest::name).toList());

        // Deep copy of trace with each entry's "result" string capped.
        batch.setTraceSoFar(capTrace(trace));

        // Fingerprint over sorted gated (name + "|" + arguments).
        batch.setFingerprint(fingerprint(gateResult.gated()));

        return batch;
    }

    /** Caps a string to at most maxBytes UTF-8 bytes without splitting a char. */
    private static String capUtf8(String s, int maxBytes) {
        if (s == null) {
            return null;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int end = maxBytes;
        // Back off to a char boundary (avoid splitting a multi-byte sequence).
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** Deep-copies the trace, capping each entry's "result" string. */
    private static List<Map<String, Object>> capTrace(List<Map<String, Object>> trace) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> entry : trace) {
            Map<String, Object> e = new HashMap<>(entry);
            Object result = e.get("result");
            if (result instanceof String rs && rs.getBytes(StandardCharsets.UTF_8).length > PendingToolCallBatch.TRACE_ENTRY_MAX_BYTES) {
                e.put("result", capUtf8(rs, PendingToolCallBatch.TRACE_ENTRY_MAX_BYTES));
            }
            copy.add(e);
        }
        return copy;
    }

    /** sha256Hex of sorted gated (name + "|" + arguments) joined by newline. */
    private static String fingerprint(List<ToolExecutionRequest> gated) {
        List<String> parts = new ArrayList<>();
        for (ToolExecutionRequest req : gated) {
            parts.add(req.name() + "|" + (req.arguments() != null ? req.arguments() : ""));
        }
        Collections.sort(parts);
        String joined = String.join("\n", parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; treat as unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Task 10 — records the pause-cap guard activation as BOTH a metric
     * ({@code eddi_hitl_tool_guard_count{guard=pause_cap}}) and an audit-ledger
     * entry ({@code hitl.tool.pause_cap}). The metric uses the Micrometer global
     * registry (AgentOrchestrator is not CDI and has no injected registry — same
     * idiom as {@code LifecycleManager}); the audit is submitted through the live
     * memory's audit collector (the seam that carries the audit context, wired by
     * {@code ConversationService} on the say/resume paths). Best-effort: any
     * failure is swallowed so guard bookkeeping never breaks the LLM loop.
     */
    private void recordPauseCapGuard(IConversationMemory memory, String fingerprint) {
        try {
            Metrics.globalRegistry.counter("eddi_hitl_tool_guard_count", "guard", "pause_cap").increment();
        } catch (Exception e) {
            LOGGER.debugf("pause_cap metric emit failed: %s", e.getMessage());
        }
        try {
            var collector = memory.getAuditCollector();
            if (collector == null) {
                return;
            }
            var detail = new LinkedHashMap<String, Object>();
            detail.put("guard", "pause_cap");
            detail.put("decidedBy", "system:pause-cap");
            detail.put("automated", true);
            if (fingerprint != null) {
                detail.put("fingerprint", fingerprint);
            }
            collector.collect(new ai.labs.eddi.engine.audit.model.AuditEntry(
                    UUID.randomUUID().toString(), memory.getConversationId(), null, null, memory.getUserId(),
                    null, -1, "hitl.tool.pause_cap", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    java.time.Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.debugf("pause_cap audit emit failed: %s", e.getMessage());
        }
    }

    /**
     * Parses the discover_tools JSON result and activates matching built-in tool
     * specs so the LLM can call them on subsequent iterations.
     */
    private void activateDiscoveredTools(String discoverResult,
                                         List<ToolSpecification> builtInSpecs,
                                         List<ToolSpecification> activeSpecs) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(discoverResult);
            JsonNode toolsNode = root.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                return;
            }

            Set<String> discoveredNames = new HashSet<>();
            for (JsonNode tool : toolsNode) {
                if (tool.has("name")) {
                    discoveredNames.add(tool.get("name").asText());
                }
            }

            // Add matching specs (skip discover_tools itself and already-active specs)
            Set<String> activeNames = new HashSet<>();
            for (ToolSpecification spec : activeSpecs) {
                activeNames.add(spec.name());
            }

            int activated = 0;
            for (ToolSpecification spec : builtInSpecs) {
                if (discoveredNames.contains(spec.name()) && !activeNames.contains(spec.name())) {
                    activeSpecs.add(spec);
                    activated++;
                }
            }

            LOGGER.infof("LAZY activation: %d tools activated from discovery (%s)",
                    activated, discoveredNames);
        } catch (Exception e) {
            LOGGER.warnf("Failed to parse discover_tools result for LAZY activation: %s",
                    e.getMessage());
        }
    }

    /**
     * Collects enabled built-in tools based on task configuration.
     * <p>
     * When {@link LlmConfiguration.ToolLoadingStrategy#LAZY} is set, ALL tools are
     * returned (so executors get registered), plus a {@link DiscoverToolsTool}
     * meta-tool. The {@code executeWithTools} method handles presenting only
     * {@code discover_tools} spec initially and activating matching specs after
     * discovery.
     */
    List<Object> collectEnabledTools(LlmConfiguration.Task task, IConversationMemory memory) {
        List<Object> tools = new ArrayList<>();

        if (task.getEnableBuiltInTools() == null || !task.getEnableBuiltInTools()) {
            return tools;
        }

        // Collect the full set of tools first (needed for both EAGER and LAZY)
        List<Object> allTools = collectAllBuiltInTools(task, memory);

        // LAZY strategy: return ALL tools + DiscoverToolsTool (so executors get
        // registered)
        // The executeWithTools method handles initially presenting only discover_tools
        if (task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY) {
            // Build tool specs from all available tools for discovery
            List<ToolSpecification> allSpecs = new ArrayList<>();
            for (Object tool : allTools) {
                Class<?> toolClass = tool.getClass();
                if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
                    toolClass = toolClass.getSuperclass();
                }
                allSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(toolClass));
            }
            int maxToolsInContext = task.getMaxToolsInContext();
            allTools.add(new DiscoverToolsTool(allSpecs, maxToolsInContext));
            LOGGER.infof("LAZY tool loading: %d built-in tools + discover_tools meta-tool registered", allSpecs.size());
            return allTools;
        }

        // EAGER strategy (default): return all tools directly
        LOGGER.info("Enabled " + allTools.size() + " built-in tools for agent");
        return allTools;
    }

    /**
     * Collects all built-in tools without considering loading strategy.
     */
    private List<Object> collectAllBuiltInTools(LlmConfiguration.Task task, IConversationMemory memory) {
        List<Object> tools = new ArrayList<>();
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
            if (whitelist.contains("fetch_page") || whitelist.contains("fetch_tool_response_page"))
                tools.add(fetchToolResponsePageTool);
            if (whitelist.contains("usermemory"))
                addUserMemoryToolIfEnabled(tools, memory);
            if (whitelist.contains("conversationRecall"))
                addConversationRecallToolIfEnabled(tools, task, memory);
            if (whitelist.contains("readattachment"))
                addReadAttachmentToolIfEnabled(tools, memory);
            // Dynamic agent tools (whitelist-gated, shared tracking lists)
            {
                List<String> sharedCreatedIds = new java.util.concurrent.CopyOnWriteArrayList<>();
                Set<String> sharedRetainedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
                String parentAgentId = memory.getAgentId();
                String userId = memory.getUserId();
                DynamicAgentConfig dynamicConfig = resolveDynamicAgentConfig(memory);

                boolean anyDynamicToolAdded = false;
                if (whitelist.contains("create_sub_agent") && agentSetupService != null && conversationService != null) {
                    tools.add(new CreateSubAgentTool(agentSetupService,
                            conversationService, parentAgentId, userId, dynamicConfig,
                            sharedCreatedIds, sharedRetainedIds));
                    LOGGER.debugf("[DYNAMIC] CreateSubAgentTool enabled for agent='%s'", sanitize(parentAgentId));
                    anyDynamicToolAdded = true;
                }
                if (whitelist.contains("converse_with_agent") && conversationService != null) {
                    tools.add(new ConverseWithAgentTool(conversationService, userId));
                    LOGGER.debugf("[DYNAMIC] ConverseWithAgentTool enabled for agent='%s'", sanitize(parentAgentId));
                }
                if (whitelist.contains("find_agents_by_capability") && capabilityRegistryService != null) {
                    tools.add(new FindAgentsByCapabilityTool(capabilityRegistryService));
                    LOGGER.debugf("[DYNAMIC] FindAgentsByCapabilityTool enabled for agent='%s'", sanitize(parentAgentId));
                }
                if (whitelist.contains("teardown_agent") && agentFactory != null && agentStore != null) {
                    tools.add(new TeardownAgentTool(agentFactory, agentStore, sharedCreatedIds, sharedRetainedIds));
                    LOGGER.debugf("[DYNAMIC] TeardownAgentTool enabled for agent='%s'", sanitize(parentAgentId));
                    anyDynamicToolAdded = true;
                }

                // Store tracking lists in memory step data so GroupConversationService
                // can read them from the snapshot after each member turn and propagate
                // to GroupConversation for lifecycle cleanup (Copilot PR review fix).
                // The lists are stored by reference — after tool execution, they'll
                // contain all agent IDs accumulated during this turn.
                if (anyDynamicToolAdded) {
                    memory.getCurrentStep().storeData(
                            new ai.labs.eddi.engine.memory.model.Data<>(KEY_DYNAMIC_CREATED_AGENT_IDS, sharedCreatedIds));
                    memory.getCurrentStep().storeData(
                            new ai.labs.eddi.engine.memory.model.Data<>(KEY_DYNAMIC_RETAINED_AGENT_IDS, sharedRetainedIds));
                }
            }
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
            tools.add(fetchToolResponsePageTool);
            // Auto-add user memory tool if agent has it enabled
            addUserMemoryToolIfEnabled(tools, memory);
            // Auto-add conversation recall tool if rolling summary is active
            addConversationRecallToolIfEnabled(tools, task, memory);
            // Auto-add the readAttachment tool when this turn has attachments
            addReadAttachmentToolIfEnabled(tools, memory);
        }

        return tools;
    }

    /**
     * Constructs and adds a UserMemoryTool if the agent has persistent user memory
     * enabled. The tool is created per-invocation with conversation-specific
     * context.
     */
    private void addUserMemoryToolIfEnabled(List<Object> tools, IConversationMemory memory) {
        AgentConfiguration.UserMemoryConfig config = memory.getUserMemoryConfig();
        if (config == null || userMemoryStore == null)
            return;

        // Extract groupIds from conversation properties (injected by
        // GroupConversationService)
        List<String> groupIds = List.of();
        var props = memory.getConversationProperties();
        if (props != null) {
            Object groupIdProp = props.get("groupId");
            if (groupIdProp instanceof Property p && p.getValueString() != null) {
                groupIds = List.of(p.getValueString());
            }
        }

        var tool = new UserMemoryTool(userMemoryStore, memory.getUserId(), memory.getAgentId(), memory.getConversationId(), groupIds, config);
        tools.add(tool);
        LOGGER.infof("[MEMORY] UserMemoryTool enabled for agent='%s', user='%s', groups=%s", sanitize(memory.getAgentId()),
                sanitize(memory.getUserId()), groupIds.stream().map(g -> sanitize(g)).toList());
    }

    /**
     * Constructs and adds a ConversationRecallTool if a rolling summary is active.
     * The tool is created per-invocation with the conversation's output list and
     * the summary step boundary.
     */
    private void addConversationRecallToolIfEnabled(List<Object> tools, LlmConfiguration.Task task, IConversationMemory memory) {
        // Only add if rolling summary is configured and a summary exists
        var summaryConfig = task.getConversationSummary();
        if (summaryConfig == null || !summaryConfig.isEnabled())
            return;

        String existingSummary = ConversationSummarizer.readSummary(memory);
        if (existingSummary == null)
            return;

        int throughStep = ConversationSummarizer.readSummaryThroughStep(memory);
        var tool = new ConversationRecallTool(List.copyOf(memory.getConversationOutputs()), throughStep, summaryConfig.getMaxRecallTurns());
        tools.add(tool);
        LOGGER.infof("[RECALL] ConversationRecallTool enabled: summaryThroughStep=%d, maxRecallTurns=%d", throughStep,
                summaryConfig.getMaxRecallTurns());
    }

    /**
     * Constructs and adds a {@link ReadAttachmentTool} when this turn carries
     * attachments, giving the LLM on-demand access to attachment text (recall of an
     * earlier turn's file, oversize files not inlined, page-targeted PDF reads).
     * The conversation id is implicit — the tool never takes it as a parameter.
     */
    private void addReadAttachmentToolIfEnabled(List<Object> tools, IConversationMemory memory) {
        if (attachmentStore == null || attachmentTextExtractor == null) {
            return;
        }
        // Exact-match read (getData, not the prefix-scanning getLatestData):
        // "attachments"
        // is a prefix of the attachments:extracts/errors keys the forwarder persists.
        IData<List<?>> attachmentData = memory.getCurrentStep().getData(MemoryKeys.ATTACHMENTS);
        if (attachmentData == null || attachmentData.getResult() == null || attachmentData.getResult().isEmpty()) {
            return;
        }
        var tool = new ReadAttachmentTool(attachmentStore, attachmentTextExtractor, memory.getConversationId());
        tools.add(tool);
        LOGGER.infof("[ATTACHMENTS] ReadAttachmentTool enabled for conversation='%s' with %d attachment(s)",
                sanitize(memory.getConversationId()), attachmentData.getResult().size());
    }

    /**
     * Resolves the DynamicAgentConfig for the current conversation. If the agent is
     * participating in a group discussion, the group's {@link DynamicAgentConfig}
     * is passed via context variable {@code dynamicAgentConfig} by
     * {@code GroupConversationService}. If no group config is present (standalone
     * agent), a permissive default is used.
     *
     * @param memory
     *            the conversation memory to check for group context
     * @return the resolved DynamicAgentConfig — group-level if available,
     *         permissive default otherwise
     */
    private DynamicAgentConfig resolveDynamicAgentConfig(IConversationMemory memory) {
        // Check if GroupConversationService injected a DynamicAgentConfig via context
        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            var contextData = currentStep.getLatestData("context:dynamicAgentConfig");
            if (contextData != null) {
                Object value = contextData.getResult();
                if (value instanceof ai.labs.eddi.engine.model.Context ctx && ctx.getValue() instanceof DynamicAgentConfig groupConfig) {
                    LOGGER.debugf("[DYNAMIC] Using group-level DynamicAgentConfig for agent='%s'", sanitize(memory.getAgentId()));
                    return groupConfig;
                }
            }
        }
        // Fallback: standalone agent — use permissive defaults
        return createDefaultDynamicConfig();
    }

    /**
     * Creates a default DynamicAgentConfig for agents without explicit group
     * config. Used when individual agents have dynamic agent tools in their
     * whitelist.
     */
    private DynamicAgentConfig createDefaultDynamicConfig() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);
        config.setAllowRecruitment(true);
        config.setAllowDelegation(true);
        return config;
    }

    // --- Httpcall auto-discovery from workflow ---

    /**
     * Result of httpcall tool discovery.
     */
    record HttpCallToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
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
            LOGGER.infof("Discovering httpcall tools for agent: %s v%s", memory.getAgentId(), memory.getAgentVersion());

            var stepConfigs = WorkflowTraversal.discoverConfigs(memory, HTTPCALLS_TYPE, ApiCallsConfiguration.class, restAgentStore,
                    restWorkflowStore, resourceClientLibrary);

            for (var stepConfig : stepConfigs) {
                ApiCallsConfiguration httpCallsConfig = stepConfig.config();
                String targetServerUrl = httpCallsConfig.getTargetServerUrl();

                for (ApiCall apiCall : httpCallsConfig.getHttpCalls()) {
                    if (apiCall.getName() == null || apiCall.getName().isBlank()) {
                        continue;
                    }

                    ToolSpecification.Builder specBuilder = ToolSpecification.builder().name(apiCall.getName())
                            .description(apiCall.getDescription() != null ? apiCall.getDescription() : "Execute " + apiCall.getName());

                    if (apiCall.getParameters() != null && !apiCall.getParameters().isEmpty()) {
                        var schemaBuilder = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder();
                        for (var param : apiCall.getParameters().entrySet()) {
                            schemaBuilder.addStringProperty(param.getKey(), param.getValue() != null ? param.getValue() : param.getKey());
                        }
                        schemaBuilder.required(new ArrayList<>(apiCall.getParameters().keySet()));
                        specBuilder.parameters(schemaBuilder.build());
                    }

                    toolSpecs.add(specBuilder.build());

                    executors.put(apiCall.getName(), (toolRequest, memoryId) -> {
                        try {
                            Map<String, Object> templateData = memoryItemConverter.convert(memory);

                            if (toolRequest.arguments() != null && !toolRequest.arguments().isBlank()) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> args = jsonSerialization.deserialize(toolRequest.arguments(), Map.class);
                                    safeTemplateMerge(templateData, args);
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
                            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                            String escaped = new String(JsonStringEncoder.getInstance().quoteAsString(errorMsg));
                            return "{\"error\": \"" + escaped + "\"}";
                        }
                    });
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " httpcall tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover httpcall tools from workflow", e);
        }

        return new HttpCallToolsResult(toolSpecs, executors);
    }

    // --- McpCalls auto-discovery from workflow ---

    /**
     * Discovers mcpcalls configurations from the workflow and creates filtered
     * ToolSpecification + ToolExecutor pairs via McpToolProviderManager.
     * <p>
     * Traverses: memory → agentId/version → AgentConfiguration → workflows →
     * WorkflowConfiguration → filter mcpCalls steps → load McpCallsConfiguration →
     * apply whitelist/blacklist → return filtered tools.
     */
    McpToolProviderManager.McpToolsResult discoverMcpCallTools(IConversationMemory memory) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();

        try {
            LOGGER.infof("Discovering mcpcalls tools for agent: %s v%s", memory.getAgentId(), memory.getAgentVersion());

            var stepConfigs = WorkflowTraversal.discoverConfigs(memory, MCPCALLS_TYPE, McpCallsConfiguration.class, restAgentStore, restWorkflowStore,
                    resourceClientLibrary);

            for (var stepConfig : stepConfigs) {
                McpCallsConfiguration mcpCallsConfig = stepConfig.config();

                // Build server config from McpCallsConfiguration
                McpServerConfig serverConfig = new McpServerConfig();
                serverConfig.setUrl(mcpCallsConfig.getMcpServerUrl());
                serverConfig.setName(mcpCallsConfig.getName());
                serverConfig.setTransport(mcpCallsConfig.getTransport());
                serverConfig.setApiKey(mcpCallsConfig.getApiKey());
                serverConfig.setTimeoutMs(mcpCallsConfig.getTimeoutMs());

                // Discover tools from this MCP server
                McpToolProviderManager.McpToolsResult result = mcpToolProviderManager.discoverTools(List.of(serverConfig));

                // Apply whitelist/blacklist filtering
                List<String> whitelist = mcpCallsConfig.getToolsWhitelist();
                List<String> blacklist = mcpCallsConfig.getToolsBlacklist();

                for (ToolSpecification spec : result.toolSpecs()) {
                    String name = spec.name();
                    if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(name))
                        continue;
                    if (blacklist != null && blacklist.contains(name))
                        continue;

                    toolSpecs.add(spec);
                    ToolExecutor executor = result.executors().get(name);
                    if (executor != null) {
                        executors.put(name, executor);
                    }
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " mcpcalls tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover mcpcalls tools from workflow", e);
        }

        return new McpToolProviderManager.McpToolsResult(toolSpecs, executors);
    }

    // ─── Template data protection ───

    /**
     * Keys produced by {@link IMemoryItemConverter#convert} that carry
     * authenticated identity and session state. LLM-provided tool arguments must
     * never override these — a prompt-injection attack could otherwise manipulate
     * userId/agentId in HTTP call templates.
     */
    private static final Set<String> RESERVED_TEMPLATE_KEYS = Set.of(
            "context", "properties", "memory",
            "userInfo", "conversationInfo", "conversationLog");

    /**
     * Merge LLM tool arguments into template data, blocking any keys that collide
     * with internal pipeline data. Blocked keys are logged as warnings so config
     * authors can rename their parameters if needed.
     */
    private static void safeTemplateMerge(Map<String, Object> templateData, Map<String, Object> args) {
        for (var entry : args.entrySet()) {
            if (RESERVED_TEMPLATE_KEYS.contains(entry.getKey())) {
                LOGGER.warnf("Blocked LLM tool argument '%s' — collides with reserved template key. " +
                        "Rename the httpcall parameter to avoid this conflict.", entry.getKey());
                continue;
            }
            templateData.put(entry.getKey(), entry.getValue());
        }
    }
}
