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
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceRegistry;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import ai.labs.eddi.engine.tenancy.TenantQuotaService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
class AgentOrchestrator implements IAgentOrchestrator {
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
    static final int DEFAULT_TRANSCRIPT_MAX_BYTES = PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT;
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
    static final boolean BUDGET_ENFORCE_DEFAULT = resolveBudgetEnforceDefault();

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
    static void warnAboutUnenforcedBudgets(LlmConfiguration.Task task) {
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

    /**
     * The live tool-calling loop, extracted to {@link ToolLoopRunner} (R2 step 5).
     */
    private final ToolLoopRunner toolLoopRunner;

    /** The HITL resume path, extracted to {@link ToolLoopResumer} (R2 step 5). */
    private final ToolLoopResumer toolLoopResumer;

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
     * I5's two dependencies, field-injected rather than constructor-injected for
     * the same reason as the stores above: the orchestrator is constructed directly
     * by a large number of test classes, and widening the constructor would touch
     * every one. {@code GroupTaskToolsProvider} treats either being null as "no
     * task tools", which is also what a non-group turn means.
     */
    @Inject
    volatile LiveDiscussionRegistry liveDiscussionRegistry;

    @Inject
    volatile IAgentGroupStore agentGroupStore;

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
    final ConversationHistoryBuilder conversationHistoryBuilder;

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
        this.toolLoopRunner = new ToolLoopRunner(toolExecutionService, toolResponseTruncator, tenantQuotaService,
                memorySnapshotService, toolApprovalGate, gateSupport, toolContextBudgetGuard);
        this.toolLoopResumer = new ToolLoopResumer(this, toolLoopRunner, gateSupport, chatTranscriptCodec, journalStore);
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
    @Override
    public ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages,
                                                 LlmConfiguration.Task task,
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

    /** @see ToolLoopResumer#resumeToolLoop */
    @Override
    public ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory,
                                          PendingToolCallBatch batch,
                                          HitlDecision decision, boolean toolHitlEnabled,
                                          JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {
        // buildToolSetup is called HERE, on this instance, rather than inside the
        // resumer through its back-reference: the resumer captured the orchestrator at
        // construction, so resolving it there would bypass a spy/override of this
        // method — and the toolRequestResolvers map it produces is what enforces
        // request pinning on resume.
        return toolLoopResumer.resumeToolLoop(chatModel, task, memory, batch, decision, toolHitlEnabled, jsonPolicy,
                buildToolSetup(task, memory));
    }

    /** Journal-stored result cap (bytes) — matches the journal store's own cap. */
    static final int JOURNAL_RESULT_MAX_BYTES = 32_768;

    /** @see ToolLoopResumer#restoreActiveSpecs */
    private static List<ToolSpecification> restoreActiveSpecs(ToolSetup setup, boolean isLazy, List<String> activatedToolNames) {
        return ToolLoopResumer.restoreActiveSpecs(setup, isLazy, activatedToolNames);
    }

    /** @see ToolLoopResumer#fallbackRebuildMessages */
    private List<ChatMessage> fallbackRebuildMessages(LlmConfiguration.Task task, IConversationMemory memory,
                                                      PendingToolCallBatch batch) {
        return toolLoopResumer.fallbackRebuildMessages(task, memory, batch);
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
    /** @see ToolLoopResumer#rebuiltRequest */
    private static ToolExecutionRequest rebuiltRequest(PendingToolCallBatch.PendingToolCall c) {
        return ToolLoopResumer.rebuiltRequest(c);
    }

    /** @see ToolLoopResumer#rebuiltRequest */
    private static ToolExecutionRequest rebuiltRequest(PendingToolCallBatch.PendingToolCall c, String args) {
        return ToolLoopResumer.rebuiltRequest(c, args);
    }

    /** @see ToolLoopResumer#rejectionEnvelope */
    private String rejectionEnvelope(String toolName, String note) {
        return toolLoopResumer.rejectionEnvelope(toolName, note);
    }

    /** @see ToolLoopResumer#amendedEnvelope */
    private String amendedEnvelope(String result) {
        return toolLoopResumer.amendedEnvelope(result);
    }

    /**
     * Reusable Jackson mapper for approver/model-facing envelopes (escapes text).
     */

    /** @see ToolLoopResumer#toJson */
    private static String toJson(Object value) {
        return ToolLoopResumer.toJson(value);
    }

    /** @see ToolLoopResumer#auditOutcomeUnknown */
    void auditOutcomeUnknown(IConversationMemory memory, PendingToolCallBatch.PendingToolCall c) {
        toolLoopResumer.auditOutcomeUnknown(memory, c);
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
     * @param toolRequestResolvers
     *            dispatch name → how to resolve what the tool would send, http
     *            source only. Populated by the registry only for names whose http
     *            spec actually survived collision resolution, so a builtin that won
     *            a collision can never be pinned against the losing http tool's
     *            request
     */
    record ToolSetup(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> toolExecutors,
            Map<String, String> toolSources, List<ToolSpecification> builtInSpecs,
            Map<String, String> toolCanonicalNames, Map<String, String> toolEndpoints,
            Map<String, ToolRequestResolver> toolRequestResolvers) {
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
                new GroupTaskToolsProvider(liveDiscussionRegistry, agentGroupStore),
                new AttachmentToolsProvider(contextual)), ctx);

        // LAZY registers every built-in's executor but shows the model only
        // discover_tools until it asks. The meta-tool advertises the specs phase 1
        // just contributed, so it can only be built here — between the two phases —
        // and it must land before the builtInSpecs snapshot, exactly as it did when
        // collectEnabledTools appended it to the list reflection then ran over.
        //
        // Gated on built-ins being enabled, restoring the pre-R2 shape: the path this
        // replaced (collectEnabledTools) returned BEFORE its LAZY branch when
        // enableBuiltInTools was null/false. Without the gate, an agent with
        // built-ins off, LAZY, and no http/mcp/a2a tools went from an empty toolSpecs
        // — which makes buildToolList return null and the turn fall back to legacy
        // non-tool completion — to a single discover_tools spec, entering the full
        // tool loop and being offered a meta-tool that can activate nothing. A
        // different request shape and a different cost, from a refactor billed as a
        // pure move.
        Boolean builtInsEnabled = task.getEnableBuiltInTools();
        if (task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY
                && builtInsEnabled != null && builtInsEnabled) {
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
                assembled.toolEndpoints(), assembled.toolRequestResolvers());
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

    /** @see ToolLoopRunner#computeInitialActiveSpecs */
    private static List<ToolSpecification> computeInitialActiveSpecs(ToolSetup setup, boolean isLazy) {
        return ToolLoopRunner.computeInitialActiveSpecs(setup, isLazy);
    }

    /** @see ToolLoopRunner#executeWithTools */
    private ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, ToolSetup setup,
                                             LlmConfiguration.Task task, IConversationMemory memory,
                                             ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex, int transcriptMaxBytes,
                                             JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {
        return toolLoopRunner.executeWithTools(chatModel, systemMessage, chatMessages, setup, task, memory,
                effectiveToolApprovals, llmTaskIndex, transcriptMaxBytes, jsonPolicy);
    }

    /** @see ToolLoopRunner#conversationToolCost */
    private double conversationToolCost(String conversationId) {
        return toolLoopRunner.conversationToolCost(conversationId);
    }

    /** @see ToolLoopRunner#toolCostDelta */
    private double toolCostDelta(String conversationId, double costBefore) {
        return toolLoopRunner.toolCostDelta(conversationId, costBefore);
    }

    /** @see ToolLoopRunner#runToolCallLoop */
    private String runToolCallLoop(ChatModel chatModel, List<ChatMessage> initialMessages, List<ToolSpecification> activeSpecs,
                                   List<Map<String, Object>> trace, int startIteration, ToolSetup setup, boolean isLazy,
                                   LlmConfiguration.Task task, IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals,
                                   int llmTaskIndex, Set<String> clearedCallIds, int transcriptMaxBytes, TokenUsage[] tokenHolder,
                                   JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {
        return toolLoopRunner.runToolCallLoop(chatModel, initialMessages, activeSpecs, trace, startIteration, setup, isLazy,
                task, memory, effectiveToolApprovals, llmTaskIndex, clearedCallIds, transcriptMaxBytes, tokenHolder, jsonPolicy);
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

    /** @see ToolLoopRunner#executeSingleToolCall */
    void executeSingleToolCall(ToolExecutionRequest toolRequest, IConversationMemory memory,
                               List<ChatMessage> currentMessages, List<Map<String, Object>> trace,
                               Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                               Map<String, String> toolCanonicalNames,
                               int defaultRateLimit, Double maxBudget, String conversationId,
                               boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                               LlmConfiguration.Task task, boolean isLazy,
                               List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
        toolLoopRunner.executeSingleToolCall(toolRequest, memory, currentMessages, trace, toolExecutors, toolRateLimits,
                toolCanonicalNames, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
    }

    /** @see ToolLoopRunner#executeSingleToolCallResult */
    String executeSingleToolCallResult(ToolExecutionRequest toolRequest, IConversationMemory memory,
                                       List<Map<String, Object>> trace,
                                       Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                                       Map<String, String> toolCanonicalNames,
                                       int defaultRateLimit, Double maxBudget, String conversationId,
                                       boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                                       LlmConfiguration.Task task, boolean isLazy,
                                       List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
        return toolLoopRunner.executeSingleToolCallResult(toolRequest, memory, trace, toolExecutors, toolRateLimits,
                toolCanonicalNames, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
    }

    /** @see ToolLoopRunner#resolveRateLimit */
    static int resolveRateLimit(Map<String, Integer> toolRateLimits, String dispatchName, String canonicalName,
                                int defaultRateLimit) {
        return ToolLoopRunner.resolveRateLimit(toolRateLimits, dispatchName, canonicalName, defaultRateLimit);
    }

    /** @see ToolLoopRunner#resolveOverride */
    static Double resolveOverride(Map<String, Double> toolPricing, String dispatchName, String canonicalName) {
        return ToolLoopRunner.resolveOverride(toolPricing, dispatchName, canonicalName);
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
                pauseCountThisTurn, llmTaskIndex, toolSources, effectiveToolApprovals, transcriptMaxBytes, Map.of(), null, Map.of());
    }

    /** @see ToolApprovalGateSupport#buildPendingBatch */
    PendingToolCallBatch buildPendingBatch(List<ChatMessage> currentMessages, ToolApprovalGate.GateResult gateResult,
                                           LlmConfiguration.Task task, IConversationMemory memory, int iterationIndex,
                                           List<String> activatedToolNames, List<Map<String, Object>> trace,
                                           int pauseCountThisTurn, int llmTaskIndex,
                                           Map<String, String> toolSources, ToolApprovalsConfig effectiveToolApprovals,
                                           int transcriptMaxBytes,
                                           Map<String, ToolApprovalsConfig.ApprovalRule> ruleByCallId,
                                           ToolApprovalsConfig.ApprovalRule governingRule,
                                           Map<String, ToolRequestResolver> resolvers) {
        return gateSupport.buildPendingBatch(currentMessages, gateResult, task, memory, iterationIndex,
                activatedToolNames, trace, pauseCountThisTurn, llmTaskIndex, toolSources, effectiveToolApprovals,
                transcriptMaxBytes, ruleByCallId, governingRule, resolvers);
    }

    /** @see ToolLoopResumer#requestChangedSinceApproval */
    String requestChangedSinceApproval(PendingToolCallBatch.PendingToolCall c, String amendedArguments,
                                       Map<String, ToolRequestResolver> resolvers) {
        return toolLoopResumer.requestChangedSinceApproval(c, amendedArguments, resolvers);
    }

    /** @see ToolApprovalGateSupport#recordWriteApprovalDecision */
    void recordWriteApprovalDecision(HitlDecision.HitlVerdict verdict, String decidedBy) {
        gateSupport.recordWriteApprovalDecision(verdict, decidedBy);
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

    /** @see ToolLoopRunner#activateDiscoveredTools */
    private void activateDiscoveredTools(String discoverResult,
                                         List<ToolSpecification> builtInSpecs,
                                         List<ToolSpecification> activeSpecs) {
        toolLoopRunner.activateDiscoveredTools(discoverResult, builtInSpecs, activeSpecs);
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
                agentFactory, agentStore, deploymentStore, liveDiscussionRegistry);
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
     * @param resolvers
     *            tool name → how to resolve what it would send, without sending it
     *            (request pinning). Only httpcall tools have one — a builtin, MCP
     *            or A2A tool is not an HTTP request this side of the boundary, so
     *            there is nothing to pin.
     */
    record HttpCallToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors, Map<String, String> endpoints,
            Map<String, ToolRequestResolver> resolvers) {
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
        return new HttpCallToolsResult(contribution.specs(), contribution.executors(), contribution.toolEndpoints(),
                contribution.toolRequestResolvers());
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
