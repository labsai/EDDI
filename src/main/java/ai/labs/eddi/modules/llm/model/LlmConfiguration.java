/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.PreRequest;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Unified configuration for LlmTask supporting both legacy and declarative
 * agent modes.
 *
 * <p>
 * <strong>Legacy Mode (Backward Compatible):</strong>
 * </p>
 *
 * <pre>{@code
 * {
 * "tasks": [
 * {
 * "actions": ["help"],
 * "type": "openai",
 * "parameters": {
 * "systemMessage": "You are helpful",
 * "addToOutput": "true"
 * }
 * }
 * ]
 * }
 * }</pre>
 *
 * <p>
 * <strong>Declarative Agent Mode (Enhanced):</strong>
 * </p>
 *
 * <pre>{@code
 * {
 * "tasks": [
 * {
 * "actions": ["help"],
 * "type": "openai",
 * "parameters": {
 * "systemMessage": "You are helpful"
 * },
 * "enableBuiltInTools": true,
 * "tools": ["eddi://ai.labs.apicalls/weather?version=1"],
 * "maxBudgetPerConversation": 1.0
 * }
 * ]
 * }
 * }</pre>
 */
public record LlmConfiguration(@JsonProperty("tasks") List<Task> tasks) {

    /**
     * Task configuration supporting both simple chat and advanced agent features.
     * The task automatically switches to agent mode when tools are configured.
     *
     * <p>
     * Historical note: this class used to declare {@code enableParallelExecution}
     * and {@code parallelExecutionTimeoutMs}. Neither was ever read — the tool loop
     * in {@code AgentOrchestrator} dispatches through
     * {@code ToolExecutor.execute(ToolExecutionRequest, memoryId)} one call at a
     * time, and the reflection-based parallel machinery they were meant to switch
     * on took an {@code (instance, Method, Object[])} triple that no live dispatch
     * path can produce (MCP and A2A tools have no Java {@code Method} at all). Both
     * were removed along with that machinery rather than wired. Stored
     * configurations that still carry either key remain valid: every mapper that
     * deserializes an LLM configuration is built from
     * {@code SerializationCustomizer.configureObjectMapper}, which sets
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, so the leftover keys are ignored on
     * read and dropped on the next write.
     * </p>
     */
    public static class Task {
        // === Core Configuration (Required) ===

        /** Actions that trigger this task */
        private List<String> actions;

        /** Task identifier */
        private String id;

        /** Model type (e.g., "openai", "anthropic", "gemini") */
        private String type;

        /** Optional description */
        private String description;

        // === Legacy Parameters (Backward Compatible) ===

        /** Pre-request processing */
        private PreRequest preRequest;

        /** Task parameters (systemMessage, prompt, logSizeLimit, etc.) */
        private Map<String, String> parameters;

        /** Name for storing response object in memory */
        private String responseObjectName;

        /** Name for storing response metadata in memory */
        private String responseMetadataObjectName;

        /** Post-response processing */
        private PostResponse postResponse;

        // === Agent Mode Features (Optional - triggers agent mode when set) ===

        /**
         * List of EDDI httpcall URIs to use as tools. Setting this enables agent mode
         * with tool calling.
         */
        private List<String> tools;

        /**
         * Remote A2A agents to consume as tools. Each entry defines an A2A-compliant
         * agent whose skills become available as tool calls.
         */
        private List<A2AAgentConfig> a2aAgents;

        /**
         * Enable built-in tools (calculator, web search, datetime, etc.) Default: false
         * (opt-in for security)
         */
        private Boolean enableBuiltInTools = false;

        /**
         * Auto-discover httpcall extensions from the workflow and expose them as tools.
         * Default: true — agents automatically get tools for all httpcalls in their
         * workflow.
         */
        private Boolean enableHttpCallTools = true;

        /**
         * Auto-discover mcpcalls extensions from the workflow and expose their tools to
         * the LLM agent. Default: true — agents automatically get tools for all
         * mcpcalls configs in their workflow, filtered by whitelist/blacklist.
         */
        private Boolean enableMcpCallTools = true;

        /**
         * Whitelist of specific built-in tools to enable. Options: "calculator",
         * "datetime", "websearch", "dataformatter", "webscraper", "textsummarizer",
         * "pdfreader", "weather"
         */
        private List<String> builtInToolsWhitelist;

        /**
         * Tool loading strategy for token efficiency with large tool sets.
         * <ul>
         * <li>{@code EAGER} (default) — all tools sent to LLM in every request</li>
         * <li>{@code LAZY} — only a discover_tools meta-tool is sent initially; the LLM
         * calls it to discover available tools, which are then injected for subsequent
         * iterations</li>
         * </ul>
         *
         * @since 6.0.0
         */
        private ToolLoadingStrategy toolLoadingStrategy = ToolLoadingStrategy.EAGER;

        /**
         * Maximum number of tool specifications returned per discovery call when using
         * {@link ToolLoadingStrategy#LAZY}. Limits context window usage for agents with
         * many tools. Default: 20.
         *
         * @since 6.0.0
         */
        private int maxToolsInContext = 20;

        /**
         * Maximum conversation turns to include in context. -1 = unlimited, 0 = none,
         * default = 10
         */
        private Integer conversationHistoryLimit = 10;

        // === Conversation Window Management (Strategy 1: Token-Aware Window) ===

        /**
         * Maximum token budget for conversation history (excluding system prompt). When
         * set (> 0), replaces step-count-based conversationHistoryLimit with
         * token-aware packing. -1 = unlimited (use conversationHistoryLimit instead).
         * Default: -1 (backward compatible — uses step count).
         */
        private Integer maxContextTokens = -1;

        /**
         * Number of opening conversation steps to always include regardless of window
         * position. These steps typically contain the user's initial requirements and
         * goals. 0 = no anchoring. Default: 2. Only applies when maxContextTokens is
         * set (token-aware mode).
         */
        private Integer anchorFirstSteps = 2;

        // === Conversation Window Management (Strategy 2: Rolling Summary) ===

        /**
         * Rolling conversation summary configuration. When enabled, older turns are
         * compressed into a running summary and injected into the system message,
         * keeping the recent window verbatim. A built-in {@code conversationRecall}
         * tool allows the LLM to drill back into summarized turns.
         */
        private ConversationSummaryConfig conversationSummary;

        // === RAG Configuration (Phase 8c) ===

        /**
         * Explicit knowledge base references. Each entry names a KB from the workflow
         * and optionally overrides retrieval parameters.
         *
         * Resolution: at execution time, the RagContextProvider discovers all RAG steps
         * from the workflow (WorkflowTraversal), then matches by name.
         *
         * Example JSON: "knowledgeBases": [ { "name": "product-docs", "maxResults": 5,
         * "minScore": 0.7 }, { "name": "faq", "maxResults": 3 } ]
         */
        private List<KnowledgeBaseReference> knowledgeBases;

        /**
         * Convenience flag: auto-discover all RAG steps from the workflow. Only used
         * when knowledgeBases is null/empty. Default: false (explicit wiring
         * preferred).
         */
        private Boolean enableWorkflowRag = false;

        /**
         * Default retrieval parameters when using enableWorkflowRag=true. Ignored when
         * knowledgeBases is set (each reference has its own overrides).
         */
        private RagDefaults ragDefaults;

        /**
         * Zero-infrastructure RAG: name of an httpCall in the workflow to execute
         * before the LLM call. The httpCall's response is injected as context into the
         * system message. This is Phase 8c-0 (quick-win, no vector store needed).
         */
        private String httpCallRag;

        /**
         * Retry configuration for LLM calls.
         *
         * @see ai.labs.eddi.configs.shared.RetryConfiguration
         */
        private RetryConfiguration retry;

        // === Budget & Cost Control ===

        /**
         * Maximum TOOL budget per conversation, in dollars. Covers per-call tool prices
         * only — LLM token spend is governed separately and per run by the model
         * cascade's {@code maxCostPerRun}. Enforced unless {@link #enforceBudget} (or
         * the deployment-wide fallback) turns enforcement off.
         */
        private Double maxBudgetPerConversation;

        /**
         * Enforce {@link #maxBudgetPerConversation}. Defaults to the deployment-wide
         * {@code eddi.tools.budget.enforce-by-default} property, itself {@code false}.
         * <p>
         * This is an opt-<em>in</em>: without it a ceiling records cost but refuses
         * nothing. Built-in tools priced at $0.00 until the canonical-slug fix in this
         * release, so enforcing by default would make those ceilings bind for the first
         * time and start aborting tool calls mid-conversation on upgrade.
         * <p>
         * The cost of that choice is real and is why the engine warns: http, MCP, A2A
         * and dynamic tools dispatch under their configured name, so a tool named
         * {@code websearch}/{@code webscraper}/{@code pdfreader} <em>was</em> priced
         * and refused before this field existed. Every task carrying a ceiling without
         * this flag is named once in a WARN rather than passing silently. Cost tracking
         * runs regardless of the flag.
         */
        private Boolean enforceBudget;

        /**
         * Per-call tool prices in USD, overriding
         * {@code ToolCostTracker.DEFAULT_TOOL_PRICES}. Keyed on the canonical built-in
         * slug (the same tokens as {@code builtInToolsWhitelist}), or on an individual
         * dispatch name for finer control — a dispatch-name entry wins over a slug
         * entry for the same call. Negative values are clamped to 0.0.
         */
        private Map<String, Double> toolPricing;

        /** Enable cost tracking */
        private Boolean enableCostTracking = true;

        // === Performance Features ===

        /** Enable tool result caching */
        private Boolean enableToolCaching = true;

        /** Enable rate limiting for tools */
        private Boolean enableRateLimiting = true;

        /** Default rate limit (calls per minute) */
        private Integer defaultRateLimit = 100;

        /** Per-tool rate limits */
        private Map<String, Integer> toolRateLimits;

        /**
         * Per-tool cache partitioning, keyed by tool name. Recognized values are
         * {@code "user"} (default), {@code "conversation"} and {@code "global"} — see
         * the {@code ToolCacheScope} enum for the authoritative list.
         * <p>
         * A cached tool result is only ever served back inside its own partition, so
         * the default keeps one authenticated user's result away from every other user.
         * Set a tool to {@code "global"} ONLY when its result depends purely on its
         * arguments and never on who is asking — that is the explicit, per-tool opt-in
         * to cross-user reuse.
         *
         * @since 6.1.0
         */
        private Map<String, String> toolCacheScopes;

        /**
         * Default cache partition for tools without a {@link #toolCacheScopes} entry.
         * Unset, blank or unrecognized values mean {@code "user"}.
         *
         * @since 6.1.0
         */
        private String defaultToolCacheScope;

        /**
         * Maximum number of tool-calling loop iterations before forcing a final answer
         * (default 10).
         */
        private Integer maxToolIterations;

        /**
         * Aggregate token ceiling for the <em>in-turn tool-call context</em>: every
         * {@code AiMessage} that carries tool-execution requests together with its
         * {@code ToolExecutionResultMessage}s, accumulated across all iterations of the
         * tool-calling loop (and across a HITL pause, which replays the same
         * transcript).
         * <p>
         * Conversation history is deliberately NOT counted here — it is already
         * governed by {@link #maxContextTokens} / {@link #conversationHistoryLimit},
         * and nothing in this guard may ever drop a system, user or assistant-prose
         * message.
         * <p>
         * When the accumulated tool traffic exceeds this ceiling, the OLDEST complete
         * tool exchange — the requesting {@code AiMessage} <em>together with all of its
         * results</em> — is dropped before the next model call, repeatedly, until the
         * traffic fits or only the most recent exchange remains. The most recent
         * exchange is never dropped, so a single oversized tool result still reaches
         * the model exactly as it does today; use {@link #toolResponseLimits} for that
         * case.
         * <p>
         * Default: 60000 — high enough that no ordinary tool-using turn is touched, low
         * enough to keep a runaway loop inside a 128k context window once the system
         * prompt, the conversation history and the model's own completion are added.
         * Set {@code -1} (or {@code 0}) to disable the guard entirely and restore the
         * pre-6.1 unbounded behaviour.
         *
         * @since 6.1.0
         */
        private Integer maxToolContextTokens = 60_000;

        /**
         * Whether an outgoing chat request may carry an API-level JSON response format
         * when {@code convertToObject=true}. One of {@code "auto"} (default),
         * {@code "on"} or {@code "off"}.
         * <p>
         * {@code auto} defers to the built-in provider matrix in
         * {@link ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy}, which
         * also knows which providers reject JSON mode when the same request carries
         * tool specifications. {@code on} forces the format onto every request of this
         * task — the escape hatch for a provider or OpenAI-compatible gateway the
         * built-in table does not know yet, and it bypasses the with-tools guard.
         * {@code off} keeps enforcement prompt-only.
         * <p>
         * This never changes the model instance, only the request, so two tasks that
         * differ only here still share one cached model.
         *
         * @since 6.1.0
         */
        private String jsonResponseFormat;

        // === Multi-Model Cascade ===

        /**
         * Multi-model cascading configuration. When enabled, tries a cheap/fast model
         * first and escalates to a better model only if confidence is low.
         */
        private ModelCascadeConfig modelCascade;

        /**
         * Tool response truncation limits. When set, tool responses exceeding the
         * character limit are truncated before re-injection into the LLM context
         * window. Reduces context bloat from verbose tool outputs.
         */
        private ToolResponseLimits toolResponseLimits;

        // === Behavioral Counterweight & Identity Masking (Wave 1) ===

        /**
         * Behavioral counterweight configuration. When enabled, safety instructions are
         * injected into the system prompt to temper agent behavior. See
         * {@link CounterweightConfig}.
         *
         * @since 6.0.0
         */
        private CounterweightConfig counterweight;

        /**
         * Identity masking configuration. When enabled, masking rules are prepended to
         * the system prompt to prevent the agent from revealing its nature, model
         * names, or internal infrastructure. See {@link IdentityMaskingConfig}.
         *
         * @since 6.0.0
         */
        private IdentityMaskingConfig identityMasking;

        // === Response Validation ===

        /**
         * Response validation configuration. When enabled, validates LLM responses
         * against configurable policies (empty, truncation, content filter, refusal,
         * streaming timeout) and applies the configured remediation action.
         *
         * @since 6.0.0
         */
        private ResponseValidation responseValidation;

        /**
         * Overall wall-clock backstop, in seconds, for a streaming chat completion.
         * Only applies when streaming is active.
         * <p>
         * This is <em>not</em> a duplicate of the {@code timeout} model parameter, and
         * the two are deliberately kept separate. {@code timeout} (milliseconds) is
         * handed to the provider's HTTP client and bounds the time to the provider's
         * first response; this field bounds the entire stream, covering providers whose
         * native timeout does not fire.
         * <p>
         * When unset, the backstop is 120s, raised to cover a longer explicitly
         * configured {@code timeout} so the backstop never truncates a stream before
         * the timeout the operator asked for.
         */
        private Integer streamingTimeoutSeconds;

        /**
         * Per-task multimodal capability overrides. Each of {@code vision},
         * {@code documents}, {@code audio} may be {@code "auto"} (defer to
         * deployment/built-in defaults), {@code "on"} (force enabled) or {@code "off"}
         * (force disabled). {@code null} means all defer.
         *
         * @since 6.1.0
         */
        private MultimodalOverride multimodal;

        /**
         * Per-task tool-approval gating override (tool-level HITL). When present, it
         * FULLY REPLACES the agent-level {@code hitlConfig.toolApprovals} for this task
         * (no list merging). Absent = inherit the agent-level default.
         */
        private ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig toolApprovals;

        // === Helper Methods ===

        /**
         * Determines if this task should run in agent mode (with tools). Note:
         * enableHttpCallTools is NOT a standalone trigger — it only enhances agent mode
         * when already triggered by tools, builtInTools, or a2aAgents. Httpcall and
         * mcpcall auto-discovery is checked at execution time.
         */
        public boolean isAgentMode() {
            return (tools != null && !tools.isEmpty()) || (enableBuiltInTools != null && enableBuiltInTools)
                    || (a2aAgents != null && !a2aAgents.isEmpty());
        }

        /**
         * Gets the system message from parameters (legacy support)
         */
        public String getSystemMessage() {
            return parameters != null ? parameters.get("systemMessage") : null;
        }

        public List<String> getActions() {
            return actions;
        }

        public void setActions(List<String> actions) {
            this.actions = actions;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig getToolApprovals() {
            return toolApprovals;
        }

        public void setToolApprovals(ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig toolApprovals) {
            this.toolApprovals = toolApprovals;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public PreRequest getPreRequest() {
            return preRequest;
        }

        public void setPreRequest(PreRequest preRequest) {
            this.preRequest = preRequest;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }

        public String getResponseObjectName() {
            return responseObjectName;
        }

        public void setResponseObjectName(String responseObjectName) {
            this.responseObjectName = responseObjectName;
        }

        public String getResponseMetadataObjectName() {
            return responseMetadataObjectName;
        }

        public void setResponseMetadataObjectName(String responseMetadataObjectName) {
            this.responseMetadataObjectName = responseMetadataObjectName;
        }

        public PostResponse getPostResponse() {
            return postResponse;
        }

        public void setPostResponse(PostResponse postResponse) {
            this.postResponse = postResponse;
        }

        public List<String> getTools() {
            return tools;
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public List<A2AAgentConfig> getA2aAgents() {
            return a2aAgents;
        }

        public void setA2aAgents(List<A2AAgentConfig> a2aAgents) {
            this.a2aAgents = a2aAgents;
        }

        public Boolean getEnableBuiltInTools() {
            return enableBuiltInTools;
        }

        public void setEnableBuiltInTools(Boolean enableBuiltInTools) {
            this.enableBuiltInTools = enableBuiltInTools;
        }

        public Boolean getEnableHttpCallTools() {
            return enableHttpCallTools;
        }

        public void setEnableHttpCallTools(Boolean enableHttpCallTools) {
            this.enableHttpCallTools = enableHttpCallTools;
        }

        public Boolean getEnableMcpCallTools() {
            return enableMcpCallTools;
        }

        public void setEnableMcpCallTools(Boolean enableMcpCallTools) {
            this.enableMcpCallTools = enableMcpCallTools;
        }

        public List<String> getBuiltInToolsWhitelist() {
            return builtInToolsWhitelist;
        }

        public void setBuiltInToolsWhitelist(List<String> builtInToolsWhitelist) {
            this.builtInToolsWhitelist = builtInToolsWhitelist;
        }

        public ToolLoadingStrategy getToolLoadingStrategy() {
            return toolLoadingStrategy;
        }

        public void setToolLoadingStrategy(ToolLoadingStrategy toolLoadingStrategy) {
            this.toolLoadingStrategy = toolLoadingStrategy;
        }

        public int getMaxToolsInContext() {
            return maxToolsInContext;
        }

        public void setMaxToolsInContext(int maxToolsInContext) {
            this.maxToolsInContext = maxToolsInContext;
        }

        public Integer getConversationHistoryLimit() {
            return conversationHistoryLimit;
        }

        public void setConversationHistoryLimit(Integer conversationHistoryLimit) {
            this.conversationHistoryLimit = conversationHistoryLimit;
        }

        public Integer getMaxContextTokens() {
            return maxContextTokens;
        }

        public void setMaxContextTokens(Integer maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
        }

        public Integer getAnchorFirstSteps() {
            return anchorFirstSteps;
        }

        public void setAnchorFirstSteps(Integer anchorFirstSteps) {
            this.anchorFirstSteps = anchorFirstSteps;
        }

        public List<KnowledgeBaseReference> getKnowledgeBases() {
            return knowledgeBases;
        }

        public void setKnowledgeBases(List<KnowledgeBaseReference> knowledgeBases) {
            this.knowledgeBases = knowledgeBases;
        }

        public Boolean getEnableWorkflowRag() {
            return enableWorkflowRag;
        }

        public void setEnableWorkflowRag(Boolean enableWorkflowRag) {
            this.enableWorkflowRag = enableWorkflowRag;
        }

        public RagDefaults getRagDefaults() {
            return ragDefaults;
        }

        public void setRagDefaults(RagDefaults ragDefaults) {
            this.ragDefaults = ragDefaults;
        }

        public String getHttpCallRag() {
            return httpCallRag;
        }

        public void setHttpCallRag(String httpCallRag) {
            this.httpCallRag = httpCallRag;
        }

        public RetryConfiguration getRetry() {
            return retry;
        }

        public void setRetry(RetryConfiguration retry) {
            this.retry = retry;
        }

        public Double getMaxBudgetPerConversation() {
            return maxBudgetPerConversation;
        }

        public void setMaxBudgetPerConversation(Double maxBudgetPerConversation) {
            this.maxBudgetPerConversation = maxBudgetPerConversation;
        }

        public Boolean getEnforceBudget() {
            return enforceBudget;
        }

        public void setEnforceBudget(Boolean enforceBudget) {
            this.enforceBudget = enforceBudget;
        }

        public Map<String, Double> getToolPricing() {
            return toolPricing;
        }

        public void setToolPricing(Map<String, Double> toolPricing) {
            this.toolPricing = toolPricing;
        }

        public Boolean getEnableCostTracking() {
            return enableCostTracking;
        }

        public void setEnableCostTracking(Boolean enableCostTracking) {
            this.enableCostTracking = enableCostTracking;
        }

        public Boolean getEnableToolCaching() {
            return enableToolCaching;
        }

        public void setEnableToolCaching(Boolean enableToolCaching) {
            this.enableToolCaching = enableToolCaching;
        }

        public Boolean getEnableRateLimiting() {
            return enableRateLimiting;
        }

        public void setEnableRateLimiting(Boolean enableRateLimiting) {
            this.enableRateLimiting = enableRateLimiting;
        }

        public Integer getDefaultRateLimit() {
            return defaultRateLimit;
        }

        public void setDefaultRateLimit(Integer defaultRateLimit) {
            this.defaultRateLimit = defaultRateLimit;
        }

        public Map<String, Integer> getToolRateLimits() {
            return toolRateLimits;
        }

        public void setToolRateLimits(Map<String, Integer> toolRateLimits) {
            this.toolRateLimits = toolRateLimits;
        }

        public Map<String, String> getToolCacheScopes() {
            return toolCacheScopes;
        }

        public void setToolCacheScopes(Map<String, String> toolCacheScopes) {
            this.toolCacheScopes = toolCacheScopes;
        }

        public String getDefaultToolCacheScope() {
            return defaultToolCacheScope;
        }

        public void setDefaultToolCacheScope(String defaultToolCacheScope) {
            this.defaultToolCacheScope = defaultToolCacheScope;
        }

        public Integer getMaxToolIterations() {
            return maxToolIterations;
        }

        public void setMaxToolIterations(Integer maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }

        public Integer getMaxToolContextTokens() {
            return maxToolContextTokens;
        }

        public void setMaxToolContextTokens(Integer maxToolContextTokens) {
            this.maxToolContextTokens = maxToolContextTokens;
        }

        public String getJsonResponseFormat() {
            return jsonResponseFormat;
        }

        public void setJsonResponseFormat(String jsonResponseFormat) {
            this.jsonResponseFormat = jsonResponseFormat;
        }

        public ModelCascadeConfig getModelCascade() {
            return modelCascade;
        }

        public void setModelCascade(ModelCascadeConfig modelCascade) {
            this.modelCascade = modelCascade;
        }

        public ToolResponseLimits getToolResponseLimits() {
            return toolResponseLimits;
        }

        public void setToolResponseLimits(ToolResponseLimits toolResponseLimits) {
            this.toolResponseLimits = toolResponseLimits;
        }

        public ConversationSummaryConfig getConversationSummary() {
            return conversationSummary;
        }

        public void setConversationSummary(ConversationSummaryConfig conversationSummary) {
            this.conversationSummary = conversationSummary;
        }

        public CounterweightConfig getCounterweight() {
            return counterweight;
        }

        public void setCounterweight(CounterweightConfig counterweight) {
            this.counterweight = counterweight;
        }

        public IdentityMaskingConfig getIdentityMasking() {
            return identityMasking;
        }

        public void setIdentityMasking(IdentityMaskingConfig identityMasking) {
            this.identityMasking = identityMasking;
        }

        public ResponseValidation getResponseValidation() {
            return responseValidation;
        }

        public void setResponseValidation(ResponseValidation responseValidation) {
            this.responseValidation = responseValidation;
        }

        public Integer getStreamingTimeoutSeconds() {
            return streamingTimeoutSeconds;
        }

        public void setStreamingTimeoutSeconds(Integer streamingTimeoutSeconds) {
            this.streamingTimeoutSeconds = streamingTimeoutSeconds;
        }

        public MultimodalOverride getMultimodal() {
            return multimodal;
        }

        public void setMultimodal(MultimodalOverride multimodal) {
            this.multimodal = multimodal;
        }

    }

    /**
     * Response validation policies for LLM outputs. Each policy field controls how
     * the engine reacts to a specific anomaly in the LLM response.
     * <p>
     * Supported actions per policy:
     * <ul>
     * <li>{@code "ignore"} — do nothing (default for most signals)</li>
     * <li>{@code "warn"} — log a warning, store metadata, continue</li>
     * <li>{@code "fallback"} — substitute a static fallback message</li>
     * <li>{@code "error"} — throw a LifecycleException (triggers strict-write +
     * error digest)</li>
     * </ul>
     *
     * @since 6.0.0
     */
    public static class ResponseValidation {

        /** Master switch — validation is only applied when enabled. */
        private boolean enabled = false;

        /** Action when the LLM returns an empty or null response. Default: "warn". */
        private String onEmpty = "warn";

        /**
         * Action when the response was truncated (finishReason=LENGTH). Default:
         * "warn".
         */
        private String onTruncation = "warn";

        /** Action when the response was blocked by content filter. Default: "warn". */
        private String onContentFilter = "warn";

        /**
         * Action when the LLM refused to respond (detected by heuristic). Default:
         * "ignore".
         */
        private String onRefusal = "ignore";

        /** Action when a streaming response timed out. Default: "warn". */
        private String onStreamingTimeout = "warn";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOnEmpty() {
            return onEmpty;
        }

        public void setOnEmpty(String onEmpty) {
            this.onEmpty = onEmpty;
        }

        public String getOnTruncation() {
            return onTruncation;
        }

        public void setOnTruncation(String onTruncation) {
            this.onTruncation = onTruncation;
        }

        public String getOnContentFilter() {
            return onContentFilter;
        }

        public void setOnContentFilter(String onContentFilter) {
            this.onContentFilter = onContentFilter;
        }

        public String getOnRefusal() {
            return onRefusal;
        }

        public void setOnRefusal(String onRefusal) {
            this.onRefusal = onRefusal;
        }

        public String getOnStreamingTimeout() {
            return onStreamingTimeout;
        }

        public void setOnStreamingTimeout(String onStreamingTimeout) {
            this.onStreamingTimeout = onStreamingTimeout;
        }
    }

    /**
     * Per-task multimodal capability overrides. Each field is a tri-state token
     * {@code "auto"|"on"|"off"} parsed by
     * {@link ai.labs.eddi.modules.llm.capability.ModelCapabilityService.Support#parse}.
     * Unset fields default to {@code "auto"}.
     *
     * @since 6.1.0
     */
    public static class MultimodalOverride {
        private String vision = "auto";
        private String documents = "auto";
        private String audio = "auto";

        public String getVision() {
            return vision;
        }

        public void setVision(String vision) {
            this.vision = vision;
        }

        public String getDocuments() {
            return documents;
        }

        public void setDocuments(String documents) {
            this.documents = documents;
        }

        public String getAudio() {
            return audio;
        }

        public void setAudio(String audio) {
            this.audio = audio;
        }
    }

    /**
     * Configuration for tool response truncation. Limits the character count of
     * individual tool results before they are fed back into the LLM context.
     */
    public static class ToolResponseLimits {
        /**
         * Default maximum characters per tool response (default: 50000 = ~12k tokens)
         */
        private int defaultMaxChars = 50000;
        /** Per-tool overrides: tool name → max chars */
        private Map<String, Integer> perToolLimits;

        /**
         * Truncation strategy: "truncate" (default), "paginate", or "summarize".
         * <ul>
         * <li>{@code truncate} — hard cut at limit with note (backward compatible)</li>
         * <li>{@code paginate} — split into pages, return first page + responseId</li>
         * <li>{@code summarize} — route through cheap model, fallback to truncate</li>
         * </ul>
         *
         * @since 6.0.0
         */
        private String truncationStrategy;

        /**
         * Model to use for the "summarize" truncation strategy. When null, the
         * summarize strategy falls back to simple truncation.
         *
         * @since 6.0.0
         */
        private String summarizerModel;

        public int getDefaultMaxChars() {
            return defaultMaxChars;
        }

        public void setDefaultMaxChars(int defaultMaxChars) {
            this.defaultMaxChars = defaultMaxChars;
        }

        public Map<String, Integer> getPerToolLimits() {
            return perToolLimits;
        }

        public void setPerToolLimits(Map<String, Integer> perToolLimits) {
            this.perToolLimits = perToolLimits;
        }

        public String getTruncationStrategy() {
            return truncationStrategy;
        }

        public void setTruncationStrategy(String truncationStrategy) {
            this.truncationStrategy = truncationStrategy;
        }

        public String getSummarizerModel() {
            return summarizerModel;
        }

        public void setSummarizerModel(String summarizerModel) {
            this.summarizerModel = summarizerModel;
        }
    }

    /**
     * Configuration for an external MCP server that provides tools to the agent.
     */
    public static class McpServerConfig {
        /** URL of the MCP server (required). Example: "http://localhost:7070/mcp" */
        private String url;

        /** Optional display name for this MCP server */
        private String name;

        /** Transport type: "http" (default) or "sse" */
        private String transport = "http";

        /** Optional API key or vault reference (e.g., "${vault:my-api-key}") */
        private String apiKey;

        /** Timeout for MCP operations in milliseconds (default: 30000) */
        private Long timeoutMs = 30000L;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * Configuration for a remote A2A agent to use as a tool provider. Mirrors
     * McpServerConfig in structure.
     */
    public static class A2AAgentConfig {
        /**
         * Base URL of the remote A2A agent (e.g.,
         * "https://remote-agent.example.com/a2a/agents/xyz")
         */
        private String url;

        /** Display name for the agent (used in tool naming) */
        private String name;

        /** Optional filter — only expose specific skills (by id or name) */
        private List<String> skillsFilter;

        /**
         * API key as a vault reference (e.g., {@code ${vault:my-a2a-key}}). Raw keys
         * are strongly discouraged — always use vault references to prevent secret
         * leakage in configuration exports.
         */
        private String apiKey;

        /** Timeout for A2A operations in milliseconds (default: 30000) */
        private Long timeoutMs = 30000L;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getSkillsFilter() {
            return skillsFilter;
        }

        public void setSkillsFilter(List<String> skillsFilter) {
            this.skillsFilter = skillsFilter;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * Reference from an LLM task to a specific knowledge base in the workflow. The
     * name must match a RagConfiguration.name in the workflow.
     *
     * <p>
     * Historical note: this class used to declare {@code injectionStrategy} and
     * {@code contextTemplate}. Neither was ever read by
     * {@code RagContextProvider.retrieveContext} or {@code LlmTask} — retrieved RAG
     * context has always been appended to the system message unconditionally — so
     * both were removed rather than wired (removing a knob nothing honoured is
     * cheaper than inventing the semantics it implied). Stored configurations that
     * still carry either key remain valid: every mapper that deserializes an LLM
     * configuration is built from
     * {@code SerializationCustomizer.configureObjectMapper}, which sets
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, so the leftover keys are ignored on
     * read and dropped on the next write.
     * </p>
     */
    public static class KnowledgeBaseReference {
        /** Name of the RagConfiguration resource in the workflow */
        private String name;

        /** Override: max results (null = use KB default) */
        private Integer maxResults;

        /** Override: min similarity score (null = use KB default) */
        private Double minScore;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(Integer maxResults) {
            this.maxResults = maxResults;
        }

        public Double getMinScore() {
            return minScore;
        }

        public void setMinScore(Double minScore) {
            this.minScore = minScore;
        }
    }

    /**
     * Default retrieval parameters for enableWorkflowRag=true mode.
     *
     * <p>
     * Historical note: this class used to declare {@code injectionStrategy}. It was
     * never read — see the note on {@link KnowledgeBaseReference} — and stored
     * configurations that still carry the key deserialize unchanged.
     * </p>
     */
    public static class RagDefaults {
        private Integer maxResults = 5;
        private Double minScore = 0.6;

        public Integer getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(Integer maxResults) {
            this.maxResults = maxResults;
        }

        public Double getMinScore() {
            return minScore;
        }

        public void setMinScore(Double minScore) {
            this.minScore = minScore;
        }
    }

    /**
     * Configuration for multi-model cascading — sequential escalation from
     * cheap/fast models to expensive/powerful models based on confidence.
     */
    public static class ModelCascadeConfig {
        /** Master switch — cascade is only used when enabled */
        private boolean enabled = false;

        /**
         * Cascade strategy: "cascade" (sequential escalation, default) or "parallel"
         * (concurrent, future)
         */
        private String strategy = "cascade";

        /**
         * How to evaluate confidence: "structured_output" (default), "heuristic",
         * "judge_model", or "none"
         */
        private String evaluationStrategy = "structured_output";

        /**
         * Whether cascade also applies in agent mode (with tools). When false, agent
         * mode uses the task-level model directly.
         */
        private boolean enableInAgentMode = true;

        /** Ordered list of cascade steps (cheap → expensive) */
        private List<CascadeStep> steps;

        /**
         * Optional judge model for the {@code judge_model} evaluation strategy. Built
         * lazily via {@code ChatModelRegistry} (with vault + global-variable
         * resolution). Required when {@code evaluationStrategy = "judge_model"}.
         */
        private JudgeModelConfig judgeModel;

        /**
         * Optional overrides for the {@code heuristic} evaluation strategy. When null,
         * the built-in English defaults are used. Enables per-deployment localization
         * of hedging/refusal phrases and thresholds.
         */
        private HeuristicConfig heuristic;

        /**
         * Optional wall-clock ceiling (milliseconds) across the whole cascade. When the
         * accumulated duration reaches this ceiling, the cascade stops escalating and
         * returns the best response seen so far. Null = unlimited.
         */
        private Long maxTotalDurationMs;

        /**
         * Optional dollar ceiling for a single cascade run. Computed from captured
         * token usage and per-step pricing. When the accumulated cost reaches this
         * ceiling, the cascade stops escalating and returns the best response so far.
         * Null = unlimited. Steps without configured pricing contribute $0.
         */
        private Double maxCostPerRun;

        /** Cascade-level default input price per 1M tokens (steps may override). */
        private Double inputPricePer1M;

        /** Cascade-level default output price per 1M tokens (steps may override). */
        private Double outputPricePer1M;

        /**
         * When true, if an earlier (escalated) step scored strictly higher than the
         * finally-accepted step, the earlier step's response is returned instead.
         * Default false preserves the last-step-wins behavior.
         */
        private boolean returnBestAcrossSteps = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getEvaluationStrategy() {
            return evaluationStrategy;
        }

        public void setEvaluationStrategy(String evaluationStrategy) {
            this.evaluationStrategy = evaluationStrategy;
        }

        public boolean isEnableInAgentMode() {
            return enableInAgentMode;
        }

        public void setEnableInAgentMode(boolean enableInAgentMode) {
            this.enableInAgentMode = enableInAgentMode;
        }

        public List<CascadeStep> getSteps() {
            return steps;
        }

        public void setSteps(List<CascadeStep> steps) {
            this.steps = steps;
        }

        public JudgeModelConfig getJudgeModel() {
            return judgeModel;
        }

        public void setJudgeModel(JudgeModelConfig judgeModel) {
            this.judgeModel = judgeModel;
        }

        public HeuristicConfig getHeuristic() {
            return heuristic;
        }

        public void setHeuristic(HeuristicConfig heuristic) {
            this.heuristic = heuristic;
        }

        public Long getMaxTotalDurationMs() {
            return maxTotalDurationMs;
        }

        public void setMaxTotalDurationMs(Long maxTotalDurationMs) {
            this.maxTotalDurationMs = maxTotalDurationMs;
        }

        public Double getMaxCostPerRun() {
            return maxCostPerRun;
        }

        public void setMaxCostPerRun(Double maxCostPerRun) {
            this.maxCostPerRun = maxCostPerRun;
        }

        public Double getInputPricePer1M() {
            return inputPricePer1M;
        }

        public void setInputPricePer1M(Double inputPricePer1M) {
            this.inputPricePer1M = inputPricePer1M;
        }

        public Double getOutputPricePer1M() {
            return outputPricePer1M;
        }

        public void setOutputPricePer1M(Double outputPricePer1M) {
            this.outputPricePer1M = outputPricePer1M;
        }

        public boolean isReturnBestAcrossSteps() {
            return returnBestAcrossSteps;
        }

        public void setReturnBestAcrossSteps(boolean returnBestAcrossSteps) {
            this.returnBestAcrossSteps = returnBestAcrossSteps;
        }
    }

    /**
     * A single step in the model cascade — defines a model to try and the
     * confidence threshold required to accept its response.
     */
    public static class CascadeStep {
        /**
         * Model provider type (e.g. "openai", "anthropic"). Overrides task-level type
         * if set.
         */
        private String type;

        /**
         * Model-specific parameters. Merged with task-level params (step params win).
         */
        private Map<String, String> parameters;

        /**
         * Minimum confidence required to accept this step's response (0.0–1.0). If
         * confidence is below this threshold, escalate to the next step. Omit (null) on
         * the last step — it is always accepted.
         */
        private Double confidenceThreshold;

        /** Per-step timeout in milliseconds. Default: 30000 */
        private Long timeoutMs = 30000L;

        /**
         * Input price per 1M tokens for this step's model. Overrides the cascade-level
         * default. Used only for the cost ceiling / cost reporting. Null = no price
         * (contributes $0 to the run cost).
         */
        private Double inputPricePer1M;

        /**
         * Output price per 1M tokens for this step's model. Overrides the cascade-level
         * default. Null = no price (contributes $0 to the run cost).
         */
        private Double outputPricePer1M;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }

        public Double getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public void setConfidenceThreshold(Double confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Double getInputPricePer1M() {
            return inputPricePer1M;
        }

        public void setInputPricePer1M(Double inputPricePer1M) {
            this.inputPricePer1M = inputPricePer1M;
        }

        public Double getOutputPricePer1M() {
            return outputPricePer1M;
        }

        public void setOutputPricePer1M(Double outputPricePer1M) {
            this.outputPricePer1M = outputPricePer1M;
        }
    }

    /**
     * Judge model for the {@code judge_model} confidence evaluation strategy. A
     * separate (typically cheap) model rates the confidence of a step's response.
     */
    public static class JudgeModelConfig {
        /** Provider type (e.g. "openai", "anthropic"). */
        private String type;

        /** Model-specific parameters (model name, apiKey, etc.). */
        private Map<String, String> parameters;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }
    }

    /**
     * Optional overrides for the {@code heuristic} confidence evaluation strategy.
     * Any null field falls back to the built-in English default. Phrase matching is
     * case-insensitive. When no configured phrase matches a response, the evaluator
     * falls back to language-agnostic scoring (length + JSON-structure signals).
     */
    public static class HeuristicConfig {
        /** Phrases indicating hedging/uncertainty (scored {@code hedgingScore}). */
        private List<String> lowConfidencePhrases;

        /** Phrases indicating refusal (scored {@code refusalScore}). */
        private List<String> refusalPhrases;

        /** Responses shorter than this many characters score {@code shortScore}. */
        private Integer shortLengthThreshold;

        /** Score assigned to very short responses. Default 0.3. */
        private Double shortScore;

        /** Score assigned when a refusal phrase matches. Default 0.2. */
        private Double refusalScore;

        /** Score assigned when a hedging phrase matches. Default 0.4. */
        private Double hedgingScore;

        /**
         * Score assigned to a decent-length response with no red flags. Default 0.8.
         */
        private Double defaultScore;

        public List<String> getLowConfidencePhrases() {
            return lowConfidencePhrases;
        }

        public void setLowConfidencePhrases(List<String> lowConfidencePhrases) {
            this.lowConfidencePhrases = lowConfidencePhrases;
        }

        public List<String> getRefusalPhrases() {
            return refusalPhrases;
        }

        public void setRefusalPhrases(List<String> refusalPhrases) {
            this.refusalPhrases = refusalPhrases;
        }

        public Integer getShortLengthThreshold() {
            return shortLengthThreshold;
        }

        public void setShortLengthThreshold(Integer shortLengthThreshold) {
            this.shortLengthThreshold = shortLengthThreshold;
        }

        public Double getShortScore() {
            return shortScore;
        }

        public void setShortScore(Double shortScore) {
            this.shortScore = shortScore;
        }

        public Double getRefusalScore() {
            return refusalScore;
        }

        public void setRefusalScore(Double refusalScore) {
            this.refusalScore = refusalScore;
        }

        public Double getHedgingScore() {
            return hedgingScore;
        }

        public void setHedgingScore(Double hedgingScore) {
            this.hedgingScore = hedgingScore;
        }

        public Double getDefaultScore() {
            return defaultScore;
        }

        public void setDefaultScore(Double defaultScore) {
            this.defaultScore = defaultScore;
        }
    }

    /**
     * Configuration for rolling conversation summary (Strategy 2).
     * <p>
     * When enabled, older conversation turns are incrementally compressed into a
     * running summary. The LLM sees: {@code [system prompt + summary] +
     * [recent N turns verbatim]}. A built-in {@code conversationRecall} tool allows
     * the agent to drill back into summarized turns on demand.
     */
    public static class ConversationSummaryConfig {

        /** Master switch — summary is only generated when enabled. */
        private boolean enabled = false;

        /** LLM provider for summarization (should be cheap/fast). */
        private String llmProvider = "anthropic";

        /** Model for summarization. Default: claude-sonnet-4-6. */
        private String llmModel = "claude-sonnet-4-6";

        /** Maximum tokens for the generated summary. */
        private int maxSummaryTokens = 800;

        /**
         * When true, the summarization prompt tells the LLM to skip facts already
         * captured as persistent properties — focusing only on reasoning, sequence, and
         * implicit context.
         */
        private boolean excludePropertiesFromSummary = true;

        /**
         * Number of recent conversation steps to keep verbatim alongside the summary.
         * Everything older is covered by the rolling summary.
         */
        private int recentWindowSteps = 5;

        /**
         * Maximum number of verbatim turns returned per
         * {@code recallConversationDetail} tool invocation. Prevents flooding context
         * with recalled history.
         */
        private int maxRecallTurns = 20;

        /**
         * Custom summarization prompt override. When null, a default structured prompt
         * is used that preserves goals, decisions, reasoning, and tone.
         */
        private String summarizationPrompt;

        /**
         * Validate and sanitize configuration values. Enforces sensible floor values to
         * prevent IndexOutOfBoundsException (negative window), wasteful LLM calls (zero
         * window triggers every turn), and NPE (null provider).
         */
        public void validate() {
            if (recentWindowSteps < 1) {
                recentWindowSteps = 5;
            }
            if (maxRecallTurns < 1) {
                maxRecallTurns = 20;
            }
            if (maxSummaryTokens < 100) {
                maxSummaryTokens = 800;
            }
            if (llmProvider == null || llmProvider.isBlank()) {
                llmProvider = "anthropic";
            }
            if (llmModel == null || llmModel.isBlank()) {
                llmModel = "claude-sonnet-4-6";
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLlmProvider() {
            return llmProvider;
        }

        public void setLlmProvider(String llmProvider) {
            this.llmProvider = llmProvider;
        }

        public String getLlmModel() {
            return llmModel;
        }

        public void setLlmModel(String llmModel) {
            this.llmModel = llmModel;
        }

        public int getMaxSummaryTokens() {
            return maxSummaryTokens;
        }

        public void setMaxSummaryTokens(int maxSummaryTokens) {
            this.maxSummaryTokens = maxSummaryTokens;
        }

        public boolean isExcludePropertiesFromSummary() {
            return excludePropertiesFromSummary;
        }

        public void setExcludePropertiesFromSummary(boolean excludePropertiesFromSummary) {
            this.excludePropertiesFromSummary = excludePropertiesFromSummary;
        }

        public int getRecentWindowSteps() {
            return recentWindowSteps;
        }

        public void setRecentWindowSteps(int recentWindowSteps) {
            this.recentWindowSteps = recentWindowSteps;
        }

        public int getMaxRecallTurns() {
            return maxRecallTurns;
        }

        public void setMaxRecallTurns(int maxRecallTurns) {
            this.maxRecallTurns = maxRecallTurns;
        }

        public String getSummarizationPrompt() {
            return summarizationPrompt;
        }

        public void setSummarizationPrompt(String summarizationPrompt) {
            this.summarizationPrompt = summarizationPrompt;
        }
    }

    /**
     * Behavioral counterweight configuration. Controls engine-level safety
     * injection into LLM system prompts.
     * <p>
     * Level presets:
     * <ul>
     * <li>{@code normal} — no injection (default when absent)</li>
     * <li>{@code cautious} — declare intent, prefer clarification, verify
     * assumptions</li>
     * <li>{@code strict} — all of cautious plus one-step-at-a-time, flag state
     * changes</li>
     * </ul>
     *
     * @since 6.0.0
     */
    public static class CounterweightConfig {
        private boolean enabled = false;
        private String level = "normal";
        private String placement = "suffix";
        private List<String> customInstructions;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getPlacement() {
            return placement;
        }

        public void setPlacement(String placement) {
            this.placement = placement;
        }

        public List<String> getCustomInstructions() {
            return customInstructions;
        }

        public void setCustomInstructions(List<String> customInstructions) {
            this.customInstructions = customInstructions;
        }
    }

    /**
     * Identity masking rules. Prepended to the system prompt to prevent the agent
     * from revealing its nature, model names, or internal infrastructure.
     *
     * @since 6.0.0
     */
    public static class IdentityMaskingConfig {
        private boolean enabled = false;
        private List<String> rules = new java.util.ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getRules() {
            return rules;
        }

        public void setRules(List<String> rules) {
            this.rules = rules;
        }
    }

    /**
     * Controls how tool specifications are presented to the LLM.
     *
     * @since 6.0.0
     */
    public enum ToolLoadingStrategy {
        /** All tool specs sent in every request (default, backward compatible) */
        EAGER,
        /**
         * Only a {@code discover_tools} meta-tool is sent initially. The LLM calls it
         * to search available tools, and matching specs are injected for subsequent
         * iterations. Saves tokens for agents with many tools.
         */
        LAZY
    }
}
