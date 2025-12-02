package ai.labs.eddi.modules.langchain.model;

import ai.labs.eddi.configs.http.model.PostResponse;
import ai.labs.eddi.configs.http.model.PreRequest;
import lombok.Getter;
import lombok.Setter;

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
    @Getter
    @Setter
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
                   (enableBuiltInTools != null && enableBuiltInTools);
        }

        /**
         * Gets the system message from parameters (legacy support)
         */
        public String getSystemMessage() {
            return parameters != null ? parameters.get("systemMessage") : null;
        }
    }

    /**
     * Configuration for API call retries
     */
    @Getter
    @Setter
    public static class RetryConfiguration {
        private Integer maxAttempts = 3;
        private Long backoffDelayMs = 1000L;
        private Double backoffMultiplier = 2.0;
        private Long maxBackoffDelayMs = 10000L;
    }

    /**
     * Configuration for RAG (Retrieval-Augmented Generation)
     */
    @Getter
    @Setter
    public static class RetrievalAugmentorConfiguration {
        private String httpCall;
        private String embeddingModel;
        private String embeddingStore;
        private Integer maxResults;
        private Double minScore;
    }
}
