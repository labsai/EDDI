/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRules;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceRegistry;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.ToolNameResolver;
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
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>
 * A stateless singleton, per the pipeline-component contract: every field is
 * {@code final} except the two write-once attachment services, which are
 * deployment-scoped collaborators rather than per-conversation state. All
 * conversational state travels through the {@code IConversationMemory}
 * argument. Constructing it directly (as the unit tests do) stays supported —
 * the annotations below only add the managed instance that {@code LlmTask}
 * injects.
 */
@ApplicationScoped
class AgentOrchestrator {
    private static final Logger LOGGER = Logger.getLogger(AgentOrchestrator.class);

    /**
     * Well-known data keys for dynamic agent lifecycle tracking. Aliases of the
     * {@link DynamicAgentToolsProvider} constants (R2 step 2) — kept declared here
     * because tests reference them by this class name.
     */
    public static final String KEY_DYNAMIC_CREATED_AGENT_IDS = DynamicAgentToolsProvider.KEY_DYNAMIC_CREATED_AGENT_IDS;
    public static final String KEY_DYNAMIC_RETAINED_AGENT_IDS = DynamicAgentToolsProvider.KEY_DYNAMIC_RETAINED_AGENT_IDS;

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
     * stored config carries an explicit {@code null}. Alias of
     * {@link ToolContextBudget#DEFAULT_MAX_TOOL_CONTEXT_TOKENS} (R2 step 3) kept
     * declared here since it is reflected/referenced by class name in tests.
     *
     * @see #enforceToolContextBudget
     */
    static final int DEFAULT_MAX_TOOL_CONTEXT_TOKENS = ToolContextBudget.DEFAULT_MAX_TOOL_CONTEXT_TOKENS;

    /**
     * Deployment-wide fallback for {@code LlmConfiguration.Task#getEnforceBudget()}
     * ({@code eddi.tools.budget.enforce-by-default}, default {@code false}).
     * <p>
     * Enforcement is <em>opt-in</em>. Until this release every built-in tool priced
     * at $0.00 (the cost table was keyed on config slugs while the live path passed
     * {@code @Tool} method names), so a {@code maxBudgetPerConversation} on a
     * built-in-only agent has never once refused a call. Now that pricing works,
     * defaulting the gate to on would make those ceilings bind for the first time
     * and start refusing tool calls mid-conversation on upgrade.
     * <p>
     * That is <em>not</em> the whole story, and the other half is why
     * {@link #warnAboutUnenforcedBudgets} exists: http/MCP/A2A/dynamic tools
     * dispatch under their configured name, so an agent with a tool called
     * {@code websearch}, {@code webscraper} or {@code pdfreader} WAS priced and WAS
     * refused on the previous release. For those agents an opt-in default silently
     * removes a cost cap that was working. Silence is the unacceptable part, not
     * the default — so every stored task carrying a ceiling without an explicit
     * {@code enforceBudget} is named in a startup WARN.
     * <p>
     * Turn enforcement on per task with {@code "enforceBudget": true}, or
     * deployment-wide with {@code eddi.tools.budget.enforce-by-default=true}.
     * <p>
     * Read through {@link ConfigProvider} rather than {@code @ConfigProperty}
     * because the value is {@code static final}: CDI injects into instance fields,
     * so an injection annotation here would never fire and the constant would sit
     * at its initializer while looking configurable. (This class became an
     * {@code @ApplicationScoped} bean in PR #604 — that makes
     * {@code @ConfigProperty} viable for *instance* state, but not for this one.)
     */
    private static final boolean BUDGET_ENFORCE_DEFAULT = resolveBudgetEnforceDefault();

    private static boolean resolveBudgetEnforceDefault() {
        try {
            return ConfigProvider.getConfig()
                    .getOptionalValue("eddi.tools.budget.enforce-by-default", Boolean.class)
                    .orElse(false);
        } catch (Exception e) {
            // No MicroProfile config available (plain unit test JVM): fall back to the
            // documented default so a test JVM and a deployment agree.
            return false;
        }
    }

    /**
     * Warns, once per configured task, that a {@code maxBudgetPerConversation}
     * ceiling is present but not enforced.
     * <p>
     * The cost of an opt-in default (see {@link #BUDGET_ENFORCE_DEFAULT}) is that
     * an operator who genuinely had a working ceiling — one on http/MCP/A2A/dynamic
     * tools, which were priced by their configured name before this release — loses
     * it without noticing. This makes that loss loud instead of silent: a ceiling
     * that records but never refuses is exactly the "config that silently does
     * nothing" this release set out to remove.
     *
     * @param task
     *            the configured LLM task about to run
     */
    private static void warnAboutUnenforcedBudgets(LlmConfiguration.Task task) {
        if (task.getMaxBudgetPerConversation() == null || task.getEnforceBudget() != null || BUDGET_ENFORCE_DEFAULT) {
            return;
        }
        if (UNENFORCED_BUDGET_WARNED.add(String.valueOf(task.getId()))) {
            LOGGER.warnf("LLM task '%s' sets maxBudgetPerConversation=%s but enforceBudget is unset, "
                    + "so the ceiling records cost without ever refusing a tool call. Tool pricing was "
                    + "repaired in this release; set \"enforceBudget\": true on the task (or "
                    + "eddi.tools.budget.enforce-by-default=true) to enforce it.",
                    sanitize(String.valueOf(task.getId())), task.getMaxBudgetPerConversation());
        }
    }

    /**
     * Task ids already named by {@link #warnAboutUnenforcedBudgets}, so a per-turn
     * check does not emit a per-turn log line. Bounded by the number of configured
     * tasks, not by traffic.
     */
    private static final Set<String> UNENFORCED_BUDGET_WARNED = ConcurrentHashMap.newKeySet();

    // Stateless helpers — safe to instantiate directly (no CDI needed).
    private final ToolApprovalGate toolApprovalGate = new ToolApprovalGate();
    private final ChatTranscriptCodec chatTranscriptCodec = new ChatTranscriptCodec();

    /**
     * Gate supporting cast, extracted to {@link ToolApprovalGateSupport} (R2 step
     * 4).
     */
    private final ToolApprovalGateSupport gateSupport = new ToolApprovalGateSupport(chatTranscriptCodec);

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
    private final A2AToolProviderManager a2aToolProviderManager;

    // For httpcall auto-discovery from workflow
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

    // Injected by the container after construction, past the constructor's
    // final-field freeze; `volatile` publishes these write-once fields to the
    // per-turn reader threads. Null under direct construction (unit tests), in
    // which case the readAttachment tool is simply never added.
    //
    // Field- rather than constructor-injected only to keep the constructor's
    // signature — and therefore the eight AgentOrchestrator*Test classes that
    // call it directly — untouched.
    @Inject
    volatile IAttachmentStore attachmentStore;

    // Same reason. Handed to TeardownAgentTool so a deleted dynamic agent leaves
    // no deployment record the runtime keeps trying to redeploy.
    @Inject
    volatile IDeploymentStore deploymentStore;

    @Inject
    volatile AttachmentTextExtractor attachmentTextExtractor;

    /**
     * Test seam for supplying the attachment services to a directly-constructed
     * orchestrator (CDI populates the fields above in production). Previously this
     * was the sole wiring path, pushed in by {@code LlmTask}'s
     * {@code @PostConstruct} — which left any other future injector of this bean
     * holding an orchestrator with no attachment services, since {@code LlmTask} is
     * itself lazily created.
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

    /**
     * In-turn tool-context budget enforcement, extracted to
     * {@link ToolContextBudget} (R2 step 3). Constructed here (not a CDI bean, per
     * the collaborator pattern rule 3.0-4) since its only dependency —
     * {@code tokenCounterFactory} — is already a constructor parameter.
     * <p>
     * Named {@code toolContextBudgetGuard}, not {@code toolContextBudget} — {@code
     * runToolCallLoop} already has a local {@code int toolContextBudget} (the
     * resolved token ceiling) that would otherwise shadow this field.
     */
    private final ToolContextBudget toolContextBudgetGuard;

    /**
     * Httpcall tool discovery, extracted to {@link HttpCallToolsProvider} (R2 step
     * 2). Constructed here since all six dependencies it needs are already
     * constructor parameters.
     */
    private final HttpCallToolsProvider httpCallToolsProvider;

    /**
     * MCP tool discovery, extracted to {@link McpToolsProvider} (R2 step 2).
     */
    private final McpToolsProvider mcpToolsProvider;

    /**
     * The nine plain built-in tool beans, extracted to {@link BuiltinToolsProvider}
     * (R2 step 2). Constructed here rather than per call because every one of its
     * dependencies is a {@code final} constructor-injected bean.
     */
    private final BuiltinToolsProvider builtinToolsProvider;

    @Inject
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
        this.a2aToolProviderManager = a2aToolProviderManager;
        this.fetchToolResponsePageTool = fetchToolResponsePageTool;
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
        this.toolContextBudgetGuard = new ToolContextBudget(tokenCounterFactory);
        this.httpCallToolsProvider = new HttpCallToolsProvider(restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter);
        this.mcpToolsProvider = new McpToolsProvider(restAgentStore, restWorkflowStore, resourceClientLibrary, mcpToolProviderManager);
        this.builtinToolsProvider = new BuiltinToolsProvider(calculatorTool, dateTimeTool, webSearchTool,
                dataFormatterTool, webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool);
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
     * @return the execution result (final response + tool trace)
     */
    ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory, PendingToolCallBatch batch,
                                   HitlDecision decision, boolean toolHitlEnabled)
            throws LifecycleException {
        return resumeToolLoop(chatModel, task, memory, batch, decision, toolHitlEnabled, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * As
     * {@link #resumeToolLoop(ChatModel, LlmConfiguration.Task, IConversationMemory, PendingToolCallBatch, HitlDecision, boolean)},
     * but carrying the JSON response-format policy so the continuation's requests
     * match what the live loop would have sent.
     */
    ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory, PendingToolCallBatch batch,
                                   HitlDecision decision, boolean toolHitlEnabled,
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
        return ToolApprovalGateSupport.normalizeToolCallIds(aiMessage, effectiveToolApprovals);
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
            Map<String, String> toolCanonicalNames, Map<String, String> toolEndpoints) {
    }

    /**
     * Runs the same tool discovery + registration prologue for a task that the live
     * loop uses, so the live path and {@link #resumeToolLoop} share ONE copy.
     * Collects enabled built-in tools, discovers httpcall/mcpcall/a2a tools from
     * the workflow, builds specs + executors via reflection, and merges external
     * tools — returning the fully populated {@link ToolSetup}.
     */
    ToolSetup buildToolSetup(LlmConfiguration.Task task, IConversationMemory memory) {
        var ctx = toolAssemblyContext(task, memory);
        var merger = ToolSourceRegistry.newMerger();

        // Phase 1 — the object-producing sources, in exactly the order
        // collectAllBuiltInTools added them: the nine plain beans, then user memory
        // and conversation recall, then the dynamic-agent block, then readAttachment
        // last. That last position is why AttachmentToolsProvider exists separately
        // from ContextualToolsProvider. Every one is a provider now, so a single
        // failing source costs the turn only its own tools.
        var contextual = contextualToolsProvider();
        merger.addAll(List.of(builtinToolsProvider, contextual, dynamicAgentToolsProvider(),
                new AttachmentToolsProvider(contextual)), ctx);

        // LAZY registers every built-in's executor but shows the model only
        // discover_tools until it asks. The meta-tool advertises the specs phase 1
        // just contributed, so it can only be built here — between the two phases —
        // and it must land before the builtInSpecs snapshot, exactly as it did when
        // collectEnabledTools appended it to the list reflection then ran over.
        if (task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY) {
            merger.addContribution("builtin", discoverToolsContribution(merger.specsSoFar(), task));
        }

        // The LAZY activation target: what phase 1 produced, before any external
        // source merges in.
        List<ToolSpecification> builtInSpecs = merger.specsSoFar();

        // Phase 2 — the externally-discovered sources. Merged after phase 1 so a
        // remote tool can never displace a governed built-in of the same name.
        merger.addAll(List.of(httpCallToolsProvider, mcpToolsProvider, a2aToolsProvider()), ctx);

        var assembled = merger.build();
        return new ToolSetup(new ArrayList<>(assembled.specs()), new HashMap<>(assembled.executors()),
                new HashMap<>(assembled.toolSources()), builtInSpecs, assembled.toolCanonicalNames(),
                assembled.toolEndpoints());
    }

    /**
     * The {@code discover_tools} meta-tool as a contribution, advertising
     * {@code availableSpecs}.
     * <p>
     * Reflected here rather than inside a provider because it is not a tool source
     * at all — it is a view over what the other local sources contributed, and no
     * provider can see that from inside its own {@code contribute}.
     */
    private ToolContribution discoverToolsContribution(List<ToolSpecification> availableSpecs,
                                                       LlmConfiguration.Task task) {
        var discoverTool = new DiscoverToolsTool(availableSpecs, task.getMaxToolsInContext());
        LOGGER.infof("LAZY tool loading: %d built-in tools + discover_tools meta-tool registered", availableSpecs.size());
        var reflected = ToolObjectReflector.reflect(List.of(discoverTool));
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(),
                List.of(), reflected.toolCanonicalNames());
    }

    /**
     * A2A tool discovery, extracted to {@link A2AToolsProvider} (R2 step 2).
     * Constructed per call for symmetry with the other per-call providers; its one
     * dependency is constructor-injected, so this could be hoisted if it ever
     * mattered.
     */
    private A2AToolsProvider a2aToolsProvider() {
        return new A2AToolsProvider(a2aToolProviderManager);
    }

    /**
     * Merge one source of externally-discovered tools into the registry, refusing
     * any name that is already taken.
     * <p>
     * Finding F15: the merge used to be {@code toolSpecs.addAll} +
     * {@code toolExecutors.putAll}. Specs accumulated in a List, so a duplicate
     * name reached the model TWICE, while executors went into a Map where the last
     * write won — a remote MCP server advertising {@code calculator} silently
     * replaced the built-in one for every call the model made.
     * {@code toolsWhitelist} filters by name and cannot express "must not collide".
     * <p>
     * Precedence follows merge order: built-in beats http beats mcp beats a2a. The
     * loser is dropped, never silently substituted, and every collision is logged.
     */
    static void mergeExternalTools(List<ToolSpecification> incomingSpecs, Map<String, ToolExecutor> incomingExecutors, String source,
                                   List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> toolExecutors, Map<String, String> toolSources) {
        if (incomingSpecs == null || incomingSpecs.isEmpty()) {
            return;
        }
        for (ToolSpecification spec : incomingSpecs) {
            String name = spec.name();
            if (name == null) {
                LOGGER.warnf("Skipping %s tool with no name", source);
                continue;
            }
            if (toolExecutors.containsKey(name)) {
                String incumbent = toolSources.getOrDefault(name, "builtin");
                LOGGER.warnf("Tool name collision: %s tool '%s' clashes with the already-registered %s tool of the same name — "
                        + "the %s tool is DROPPED and the %s tool keeps the name. Rename the remote tool or exclude it via "
                        + "toolsBlacklist.", source, sanitize(name), incumbent, source, incumbent);
                continue;
            }
            ToolExecutor executor = incomingExecutors != null ? incomingExecutors.get(name) : null;
            if (executor == null) {
                LOGGER.warnf("%s tool '%s' has a specification but no executor — skipping", source, sanitize(name));
                continue;
            }
            toolSpecs.add(spec);
            toolExecutors.put(name, executor);
            toolSources.put(name, source);
        }
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
        TokenCountEstimator toolContextEstimator = toolContextBudget > 0 ? toolContextBudgetGuard.resolveToolContextEstimator(task) : null;

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
                    var gateResult = toolApprovalGate.classify(aiMessage.toolExecutionRequests(), toolSources, setup.toolEndpoints(),
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
                            // 2) resolve the per-tool friction rule (iteration 1). Done HERE,
                            // once, because this is the last place the gated calls' endpoints
                            // exist — the persisted batch keeps names and sources only, so an
                            // endpoint-addressed rule could never be re-matched downstream.
                            var ruleByCallId = ToolApprovalRules.matchByCallId(gateResult.gated(), toolSources,
                                    setup.toolEndpoints(), effectiveToolApprovals);
                            var governingRule = ToolApprovalRules.governing(ruleByCallId.values());
                            recordRuleMatches(ruleByCallId.values());
                            // 3) snapshot + persist the pending batch, then abort the loop
                            PendingToolCallBatch batch = buildPendingBatch(currentMessages, gateResult, task, memory,
                                    i, activatedToolNames(isLazy, activeSpecs), trace, pausesSoFar + 1, llmTaskIndex,
                                    toolSources, effectiveToolApprovals, transcriptMaxBytes, ruleByCallId, governingRule);
                            memory.setHitlPendingToolCalls(batch);
                            incrementToolPauseCount(memory, pausesSoFar);
                            throw new ToolApprovalRequiredException(
                                    buildPauseReason(effectiveToolApprovals, gateResult, governingRule), batch);
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

    // ─── In-turn tool-context budget (D6b) — extracted to ToolContextBudget (R2
    // step 3). Kept as declared delegators (not inlined) since they're reflected
    // or referenced by class name in tests and, for sumTokens/tokenUsageMap, by
    // LegacyChatExecutor/CascadingModelExecutor/LlmTask. ───

    static void enforceToolContextBudget(List<ChatMessage> messages, int budgetTokens, TokenCountEstimator estimator,
                                         Map<ChatMessage, Integer> tokenMemo, List<Map<String, Object>> trace,
                                         String conversationId) {
        ToolContextBudget.enforceToolContextBudget(messages, budgetTokens, estimator, tokenMemo, trace, conversationId);
    }

    static TokenUsage sumTokens(TokenUsage a, TokenUsage b) {
        return ToolContextBudget.sumTokens(a, b);
    }

    static final List<String> TOKEN_USAGE_FIELDS = ToolContextBudget.TOKEN_USAGE_FIELDS;

    static Map<String, Object> tokenUsageMap(TokenUsage usage) {
        return ToolContextBudget.tokenUsageMap(usage);
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
        // Enforcement is opt-in: enforceBudget on the task wins, otherwise
        // BUDGET_ENFORCE_DEFAULT (false unless eddi.tools.budget.enforce-by-default
        // says so). Built-in tools priced at $0.00 until this release, so enforcing
        // by default would make those ceilings bind for the first time on upgrade.
        // A ceiling left unenforced is named once in a startup-style WARN rather
        // than passing silently. Cost tracking itself is unaffected by the flag.
        warnAboutUnenforcedBudgets(task);
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
    //
    // Bodies moved to ToolApprovalGateSupport (R2 step 4). These stay as declared
    // delegators with identical signatures and modifiers, because several
    // characterization tests reach them via
    // AgentOrchestrator.class.getDeclaredMethod(...), which resolves only methods
    // declared on this exact class, and buildPendingBatch is called directly as an
    // instance method in eight places.

    /** Reads this turn's cumulative gated-pause count (0 when absent). */
    private static int readToolPauseCount(IConversationMemory memory) {
        return ToolApprovalGateSupport.readToolPauseCount(memory);
    }

    /** Writes the incremented gated-pause count for this turn. */
    private static void incrementToolPauseCount(IConversationMemory memory, int pausesSoFar) {
        ToolApprovalGateSupport.incrementToolPauseCount(memory, pausesSoFar);
    }

    /** Effective max pauses per turn (default 3, clamped 1..10). */
    private static int maxPausesPerTurn(ToolApprovalsConfig cfg) {
        return ToolApprovalGateSupport.maxPausesPerTurn(cfg);
    }

    /** @see ToolApprovalGateSupport#recordRuleMatches */
    private void recordRuleMatches(Collection<ToolApprovalsConfig.ApprovalRule> matched) {
        gateSupport.recordRuleMatches(matched);
    }

    /** Names activated in LAZY mode (for resume reactivation); empty otherwise. */
    private static List<String> activatedToolNames(boolean isLazy, List<ToolSpecification> activeSpecs) {
        return ToolApprovalGateSupport.activatedToolNames(isLazy, activeSpecs);
    }

    /** First non-blank of the two, or null. */
    private static String firstNonBlank(String preferred, String fallback) {
        return ToolApprovalGateSupport.firstNonBlank(preferred, fallback);
    }

    /** @see ToolApprovalGateSupport#buildPauseReason */
    private static String buildPauseReason(ToolApprovalsConfig cfg, ToolApprovalGate.GateResult gateResult,
                                           ToolApprovalsConfig.ApprovalRule rule) {
        return ToolApprovalGateSupport.buildPauseReason(cfg, gateResult, rule);
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
     * Backward-compatible overload: no per-tool friction rules resolved (the batch
     * falls back to the {@code toolApprovals} scalars, exactly as before iteration
     * 1).
     */
    PendingToolCallBatch buildPendingBatch(List<ChatMessage> currentMessages, ToolApprovalGate.GateResult gateResult,
                                           LlmConfiguration.Task task, IConversationMemory memory, int iterationIndex,
                                           List<String> activatedToolNames, List<Map<String, Object>> trace,
                                           int pauseCountThisTurn, int llmTaskIndex,
                                           Map<String, String> toolSources, ToolApprovalsConfig effectiveToolApprovals,
                                           int transcriptMaxBytes) {
        return buildPendingBatch(currentMessages, gateResult, task, memory, iterationIndex, activatedToolNames, trace,
                pauseCountThisTurn, llmTaskIndex, toolSources, effectiveToolApprovals, transcriptMaxBytes, Map.of(), null);
    }

    /** @see ToolApprovalGateSupport#buildPendingBatch */
    PendingToolCallBatch buildPendingBatch(List<ChatMessage> currentMessages, ToolApprovalGate.GateResult gateResult,
                                           LlmConfiguration.Task task, IConversationMemory memory, int iterationIndex,
                                           List<String> activatedToolNames, List<Map<String, Object>> trace,
                                           int pauseCountThisTurn, int llmTaskIndex,
                                           Map<String, String> toolSources, ToolApprovalsConfig effectiveToolApprovals,
                                           int transcriptMaxBytes,
                                           Map<String, ToolApprovalsConfig.ApprovalRule> ruleByCallId,
                                           ToolApprovalsConfig.ApprovalRule governingRule) {
        return gateSupport.buildPendingBatch(currentMessages, gateResult, task, memory, iterationIndex,
                activatedToolNames, trace, pauseCountThisTurn, llmTaskIndex, toolSources, effectiveToolApprovals,
                transcriptMaxBytes, ruleByCallId, governingRule);
    }

    /** Caps a string to at most maxBytes UTF-8 bytes without splitting a char. */
    private static String capUtf8(String s, int maxBytes) {
        return ToolApprovalGateSupport.capUtf8(s, maxBytes);
    }

    /** Deep-copies the trace, capping each entry's "result" string. */
    private static List<Map<String, Object>> capTrace(List<Map<String, Object>> trace) {
        return ToolApprovalGateSupport.capTrace(trace);
    }

    /** sha256Hex of sorted gated (name + pipe + arguments) joined by newline. */
    private static String fingerprint(List<ToolExecutionRequest> gated) {
        return ToolApprovalGateSupport.fingerprint(gated);
    }

    /** @see ToolApprovalGateSupport#recordPauseCapGuard */
    private void recordPauseCapGuard(IConversationMemory memory, String fingerprint) {
        gateSupport.recordPauseCapGuard(memory, fingerprint);
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
     * Collects enabled built-in tools based on task configuration, as tool
     * <em>objects</em>.
     * <p>
     * When {@link LlmConfiguration.ToolLoadingStrategy#LAZY} is set, ALL tools are
     * returned (so executors get registered), plus a {@link DiscoverToolsTool}
     * meta-tool. The {@code executeWithTools} method handles presenting only
     * {@code discover_tools} spec initially and activating matching specs after
     * discovery.
     * <p>
     * <b>No production caller since the R2 rewiring.</b> {@code buildToolSetup} now
     * assembles the same sources through {@link ToolSourceRegistry}, which works in
     * specs and executors rather than objects. This method survives because several
     * characterization tests call it directly, and it deliberately routes through
     * the very same provider instances so the two paths cannot drift: a change to
     * any provider's enablement rule shows up here too.
     * {@code AgentOrchestratorLocalToolAssemblyTest} pins that the two agree tool
     * for tool — without it, this method would be exactly the kind of dead
     * lookalike that keeps a test suite green while production diverges.
     */
    List<Object> collectEnabledTools(LlmConfiguration.Task task, IConversationMemory memory) {
        List<Object> tools = new ArrayList<>();

        if (task.getEnableBuiltInTools() == null || !task.getEnableBuiltInTools()) {
            // readAttachment is not governed by the built-in tools config at all —
            // it is part of attachment support, added here and in
            // collectAllBuiltInTools whenever the conversation actually has files.
            // See the note there for why neither the switch nor the whitelist gates
            // it.
            addReadAttachmentToolIfEnabled(tools, memory);
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

        // The nine plain tool beans, in catalog order — one call now serves both the
        // whitelist and no-whitelist cases, because "no whitelist" has always meant
        // "every entry applies". Previously these nine were listed twice, once per
        // branch; see BuiltinToolsProvider for why that duplication was worth
        // removing.
        var assemblyContext = toolAssemblyContext(task, memory);
        tools.addAll(builtinToolsProvider.collect(assemblyContext));

        if (whitelist != null && !whitelist.isEmpty()) {
            if (whitelist.contains("usermemory"))
                addUserMemoryToolIfEnabled(tools, memory);
            if (whitelist.contains("conversationRecall"))
                addConversationRecallToolIfEnabled(tools, task, memory);
            // Dynamic agent tools (whitelist-gated, shared tracking lists) —
            // extracted to DynamicAgentToolsProvider (R2 step 2). Constructed per
            // call since deploymentStore is field-injected (see that class's Javadoc).
            dynamicAgentToolsProvider().addDynamicAgentTools(tools, whitelist, memory);
        } else {
            // Auto-add user memory tool if agent has it enabled
            addUserMemoryToolIfEnabled(tools, memory);
            // Auto-add conversation recall tool if rolling summary is active
            addConversationRecallToolIfEnabled(tools, task, memory);
        }

        // Outside the whitelist branch on purpose: readAttachment is part of
        // attachment support, not a capability the built-in tools config governs.
        //
        // It only ever appears when the conversation actually has files, and it
        // reads a blob already stored under that conversation and authorized by
        // ownership or an explicit grant — no outbound call, no cost. That is
        // nothing like the web search / scraping / HTTP tools enableBuiltInTools
        // exists to gate.
        //
        // Two things make gating it actively wrong rather than merely strict.
        // AttachmentForwarder inlines a document on the turn it arrives with no
        // whitelist check at all, so excluding this tool never stopped the model
        // seeing the file — it only stopped it seeing the file on any LATER turn.
        // And the Manager could not even offer "readattachment" as a choice until
        // 6.2.0, so every whitelist written before then omits it by construction;
        // honouring that omission would leave attachment recall broken for every
        // existing whitelisted agent.
        addReadAttachmentToolIfEnabled(tools, memory);

        return tools;
    }

    /**
     * The turn's {@link ToolAssemblyContext}, built once and shared by every
     * provider so they all read one consistent snapshot.
     * <p>
     * {@code dynamicAgentConfig} in particular is resolved here rather than inside
     * each provider: it can come from a group-injected context variable, and two
     * providers resolving it independently could disagree if the step data changed
     * between reads.
     */
    ToolAssemblyContext toolAssemblyContext(LlmConfiguration.Task task, IConversationMemory memory) {
        return new ToolAssemblyContext(memory, task, task.getBuiltInToolsWhitelist(),
                DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory),
                memory.getUserId(), memory.getAgentId(), groupConversationIdOf(memory));
    }

    /**
     * The {@code groupConversationId} context variable, or null outside a group
     * discussion. Read defensively — this runs on every turn, group or not, and a
     * malformed context value must not cost the agent its entire tool set.
     */
    private static String groupConversationIdOf(IConversationMemory memory) {
        try {
            var currentStep = memory.getCurrentStep();
            if (currentStep == null) {
                return null;
            }
            var data = currentStep.getLatestData("context:groupConversationId");
            if (data != null && data.getResult() instanceof Context ctx && ctx.getValue() != null) {
                return String.valueOf(ctx.getValue());
            }
        } catch (Exception e) {
            LOGGER.debugf("groupConversationId context lookup failed: %s", e.getMessage());
        }
        return null;
    }

    /**
     * User-memory / conversation-recall / attachment tool construction, extracted
     * to {@link ContextualToolsProvider} (R2 step 2). Constructed per call —
     * {@link #attachmentStore} and {@link #attachmentTextExtractor} are
     * field-injected and still null when this class's constructor runs.
     */
    private ContextualToolsProvider contextualToolsProvider() {
        return new ContextualToolsProvider(userMemoryStore, attachmentStore, attachmentTextExtractor);
    }

    // Kept as declared delegators (not inlined) — each has two call sites in
    // collectAllBuiltInTools' whitelist/no-whitelist branches. Logic extracted to
    // ContextualToolsProvider (R2 step 2).

    private void addUserMemoryToolIfEnabled(List<Object> tools, IConversationMemory memory) {
        contextualToolsProvider().addUserMemoryToolIfEnabled(tools, memory);
    }

    private void addConversationRecallToolIfEnabled(List<Object> tools, LlmConfiguration.Task task, IConversationMemory memory) {
        contextualToolsProvider().addConversationRecallToolIfEnabled(tools, task, memory);
    }

    private void addReadAttachmentToolIfEnabled(List<Object> tools, IConversationMemory memory) {
        contextualToolsProvider().addReadAttachmentToolIfEnabled(tools, memory);
    }

    /**
     * Dynamic-agent tool construction, extracted to
     * {@link DynamicAgentToolsProvider} (R2 step 2). Constructed per call — not
     * once in the constructor like the http/mcp providers — because
     * {@link #deploymentStore} is field-injected and still null when this class's
     * constructor runs; see that class's Javadoc.
     */
    private DynamicAgentToolsProvider dynamicAgentToolsProvider() {
        return new DynamicAgentToolsProvider(agentSetupService, capabilityRegistryService, conversationService,
                agentFactory, agentStore, deploymentStore);
    }

    // Kept as declared delegators (not inlined) since tests reference them by
    // class name. Logic extracted to DynamicAgentToolsProvider (R2 step 2).

    static List<String> seedCreatedAgentIds(IConversationMemory memory) {
        return DynamicAgentToolsProvider.seedCreatedAgentIds(memory);
    }

    static int resolveDelegationDepth(IConversationMemory memory) {
        return DynamicAgentToolsProvider.resolveDelegationDepth(memory);
    }

    // --- Httpcall auto-discovery from workflow ---

    /**
     * Result of httpcall tool discovery.
     *
     * @param endpoints
     *            tool name to {@code method:path} (e.g.
     *            {@code post:/agentstore/agents}), so an approval pattern can
     *            address the endpoint a tool calls rather than its generated name.
     *            Names come from {@code operationId} or a slug, which drift; the
     *            method and path are what the agent designer actually wrote in the
     *            endpoint allow-list.
     */
    record HttpCallToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors, Map<String, String> endpoints) {
    }

    // Kept as declared delegators (not inlined) since they're reflected/hard
    // class-referenced in tests. Logic extracted to HttpCallToolsProvider (R2
    // step 2); buildToolSetup's merge flow is unchanged, so this still returns
    // the legacy HttpCallToolsResult shape (adapted from the new ToolContribution).

    static String normalizeEndpointPath(String rawPath) {
        return HttpCallToolsProvider.normalizeEndpointPath(rawPath);
    }

    HttpCallToolsResult discoverHttpCallTools(IConversationMemory memory) {
        var contribution = httpCallToolsProvider.discover(memory);
        return new HttpCallToolsResult(contribution.specs(), contribution.executors(), contribution.toolEndpoints());
    }

    // Kept as a declared delegator (not inlined). Logic extracted to
    // McpToolsProvider (R2 step 2); buildToolSetup's merge flow is unchanged, so
    // this still returns the legacy McpToolsResult shape.
    McpToolProviderManager.McpToolsResult discoverMcpCallTools(IConversationMemory memory) {
        return mcpToolsProvider.discover(memory);
    }

    // Kept as a declared delegator (not inlined) since it's reflected in tests.
    // Logic + RESERVED_TEMPLATE_KEYS extracted to HttpCallToolsProvider (R2 step
    // 2).
    private static void safeTemplateMerge(Map<String, Object> templateData, Map<String, Object> args) {
        HttpCallToolsProvider.safeTemplateMerge(templateData, args);
    }
}
