package ai.labs.eddi.modules.langchain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Configuration for custom tools defined via JSON config.
 * Phase 4: Allows users to define tools without writing Java code.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomToolConfiguration {

    /**
     * Unique name for the tool
     */
    private String name;

    /**
     * Description of what the tool does (shown to LLM)
     */
    private String description;

    /**
     * Type of custom tool: "httpcall", "script", "composite"
     */
    private ToolType type;

    /**
     * Parameters the tool accepts
     */
    private List<ToolParameter> parameters;

    /**
     * Configuration specific to the tool type
     */
    private Map<String, Object> config;

    /**
     * Whether this tool requires authentication
     */
    private boolean requiresAuth;

    /**
     * Cost per execution (for cost tracking)
     */
    private double costPerExecution;

    /**
     * Cache TTL in milliseconds
     */
    private Long cacheTtlMs;

    /**
     * Rate limit (calls per minute)
     */
    private Integer rateLimit;

    /**
     * Tool parameter definition
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolParameter {
        private String name;
        private String description;
        private String type; // "string", "number", "boolean", "object", "array"
        private boolean required;
        private Object defaultValue;
    }

    /**
     * Type of custom tool
     */
    public enum ToolType {
        /**
         * Execute an HTTP call (using EDDI's httpcalls)
         */
        HTTPCALL,

        /**
         * Execute a script (JavaScript, Python, etc.)
         */
        SCRIPT,

        /**
         * Composite tool that calls multiple other tools
         */
        COMPOSITE,

        /**
         * Database query tool
         */
        DATABASE,

        /**
         * File system operation tool
         */
        FILESYSTEM
    }
}

