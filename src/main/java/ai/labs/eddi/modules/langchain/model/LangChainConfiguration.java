package ai.labs.eddi.modules.langchain.model;

import ai.labs.eddi.configs.httpcalls.model.PostResponse;
import ai.labs.eddi.configs.httpcalls.model.PreRequest;

import java.util.List;
import java.util.Map;

/**
 * Unified configuration for LangchainTask supporting both legacy and declarative agent modes.
 *
 * <p><strong>Legacy Mode (Backward Compatible):</strong></p>
 * <pre>{@code
 * {
 *   "tasks": [
 *     {
 *       "actions": ["help"],
 *       "type": "openai",
 *       "parameters": {
 *         "systemMessage": "You are helpful",
 *         "addToOutput": "true"
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p><strong>Declarative Agent Mode (Enhanced):</strong></p>
 * <pre>{@code
 * {
 *   "tasks": [
 *     {
 *       "actions": ["help"],
 *       "type": "openai",
 *       "parameters": {
 *         "systemMessage": "You are helpful"
 *       },
 *       "enableBuiltInTools": true,
 *       "tools": ["eddi://ai.labs.httpcalls/weather?version=1"],
 *       "maxBudgetPerConversation": 1.0
 *     }
 *   ]
 * }
 * }</pre>
 */
public record LangChainConfiguration(List<Task> tasks) {

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
         * List of EDDI httpcall URIs to use as tools.
         * Setting this enables agent mode with tool calling.
         */
        private List<String> tools;

        /**
         * External MCP servers to connect to as tool providers.
         * Each entry defines a remote MCP server whose tools become available
         * to the LLM alongside built-in and EDDI httpcall tools.
         */
        private List<McpServerConfig> mcpServers;

        /**
         * Enable built-in tools (calculator, web search, datetime, etc.)
         * Default: false (opt-in for security)
         */
        private Boolean enableBuiltInTools = false;

        /**
         * Whitelist of specific built-in tools to enable.
         * Options: "calculator", "datetime", "websearch", "dataformatter",
         *          "webscraper", "textsummarizer", "pdfreader", "weather"
         */
        private List<String> builtInToolsWhitelist;

        /**
         * Maximum conversation turns to include in context.
         * -1 = unlimited, 0 = none, default = 10
         */
        private Integer conversationHistoryLimit = 10;

        /**
         * RAG configuration for knowledge augmentation
         */
        private RetrievalAugmentorConfiguration retrievalAugmentor;

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

        // === Helper Methods ===

        /**
         * Determines if this task should run in agent mode (with tools)
         */
        public boolean isAgentMode() {
            return (tools != null && !tools.isEmpty()) ||
                   (enableBuiltInTools != null && enableBuiltInTools) ||
                   (mcpServers != null && !mcpServers.isEmpty());
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

        public List<McpServerConfig> getMcpServers() {
            return mcpServers;
        }

        public void setMcpServers(List<McpServerConfig> mcpServers) {
            this.mcpServers = mcpServers;
        }

        public Boolean getEnableBuiltInTools() {
            return enableBuiltInTools;
        }

        public void setEnableBuiltInTools(Boolean enableBuiltInTools) {
            this.enableBuiltInTools = enableBuiltInTools;
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

        public RetrievalAugmentorConfiguration getRetrievalAugmentor() {
            return retrievalAugmentor;
        }

        public void setRetrievalAugmentor(RetrievalAugmentorConfiguration retrievalAugmentor) {
            this.retrievalAugmentor = retrievalAugmentor;
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
    }

    /**
     * Configuration for an external MCP server that provides tools to the bot.
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
     * Configuration for RAG (Retrieval-Augmented Generation)
     */
    public static class RetrievalAugmentorConfiguration {
        private String httpCall;
        private String embeddingModel;
        private String embeddingStore;
        private Integer maxResults;
        private Double minScore;

        public String getHttpCall() {
            return httpCall;
        }

        public void setHttpCall(String httpCall) {
            this.httpCall = httpCall;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getEmbeddingStore() {
            return embeddingStore;
        }

        public void setEmbeddingStore(String embeddingStore) {
            this.embeddingStore = embeddingStore;
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
}
