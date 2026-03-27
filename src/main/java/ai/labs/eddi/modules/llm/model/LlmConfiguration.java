package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.PreRequest;

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
public record LlmConfiguration(List<Task> tasks) {

    /**
     * Task configuration supporting both simple chat and advanced agent features.
     * The task automatically switches to agent mode when tools are configured.
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
         * Maximum conversation turns to include in context. -1 = unlimited, 0 = none,
         * default = 10
         */
        private Integer conversationHistoryLimit = 10;


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
         * Retry configuration for API calls
         */
        private RetryConfiguration retry;

        // === Budget & Cost Control ===

        /** Maximum budget per conversation (in dollars) */
        private Double maxBudgetPerConversation;

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

        /** Enable parallel tool execution */
        private Boolean enableParallelExecution = false;

        /** Timeout for parallel execution (ms) */
        private Long parallelExecutionTimeoutMs = 30000L;

        /**
         * Maximum number of tool-calling loop iterations before forcing a final answer
         * (default 10).
         */
        private Integer maxToolIterations;

        // === Multi-Model Cascade ===

        /**
         * Multi-model cascading configuration. When enabled, tries a cheap/fast model
         * first and escalates to a better model only if confidence is low.
         */
        private ModelCascadeConfig modelCascade;

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

        public Integer getConversationHistoryLimit() {
            return conversationHistoryLimit;
        }

        public void setConversationHistoryLimit(Integer conversationHistoryLimit) {
            this.conversationHistoryLimit = conversationHistoryLimit;
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

        public Boolean getEnableParallelExecution() {
            return enableParallelExecution;
        }

        public void setEnableParallelExecution(Boolean enableParallelExecution) {
            this.enableParallelExecution = enableParallelExecution;
        }

        public Long getParallelExecutionTimeoutMs() {
            return parallelExecutionTimeoutMs;
        }

        public void setParallelExecutionTimeoutMs(Long parallelExecutionTimeoutMs) {
            this.parallelExecutionTimeoutMs = parallelExecutionTimeoutMs;
        }

        public Integer getMaxToolIterations() {
            return maxToolIterations;
        }

        public void setMaxToolIterations(Integer maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }

        public ModelCascadeConfig getModelCascade() {
            return modelCascade;
        }

        public void setModelCascade(ModelCascadeConfig modelCascade) {
            this.modelCascade = modelCascade;
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
     * Configuration for API call retries
     */
    public static class RetryConfiguration {
        private Integer maxAttempts = 3;
        private Long backoffDelayMs = 1000L;
        private Double backoffMultiplier = 2.0;
        private Long maxBackoffDelayMs = 10000L;

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Long getBackoffDelayMs() {
            return backoffDelayMs;
        }

        public void setBackoffDelayMs(Long backoffDelayMs) {
            this.backoffDelayMs = backoffDelayMs;
        }

        public Double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(Double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public Long getMaxBackoffDelayMs() {
            return maxBackoffDelayMs;
        }

        public void setMaxBackoffDelayMs(Long maxBackoffDelayMs) {
            this.maxBackoffDelayMs = maxBackoffDelayMs;
        }
    }


    /**
     * Reference from an LLM task to a specific knowledge base in the workflow. The
     * name must match a RagConfiguration.name in the workflow.
     */
    public static class KnowledgeBaseReference {
        /** Name of the RagConfiguration resource in the workflow */
        private String name;

        /** Override: max results (null = use KB default) */
        private Integer maxResults;

        /** Override: min similarity score (null = use KB default) */
        private Double minScore;

        /** Override: injection strategy — "system_message" (default), "user_message" */
        private String injectionStrategy;

        /** Override: custom context template (null = use default formatting) */
        private String contextTemplate;

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

        public String getInjectionStrategy() {
            return injectionStrategy;
        }

        public void setInjectionStrategy(String injectionStrategy) {
            this.injectionStrategy = injectionStrategy;
        }

        public String getContextTemplate() {
            return contextTemplate;
        }

        public void setContextTemplate(String contextTemplate) {
            this.contextTemplate = contextTemplate;
        }
    }

    /**
     * Default retrieval parameters for enableWorkflowRag=true mode.
     */
    public static class RagDefaults {
        private Integer maxResults = 5;
        private Double minScore = 0.6;
        private String injectionStrategy = "system_message";

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

        public String getInjectionStrategy() {
            return injectionStrategy;
        }

        public void setInjectionStrategy(String injectionStrategy) {
            this.injectionStrategy = injectionStrategy;
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
    }
}
