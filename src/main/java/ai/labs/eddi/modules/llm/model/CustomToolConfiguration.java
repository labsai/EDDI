package ai.labs.eddi.modules.llm.model;


import java.util.List;
import java.util.Map;

/**
 * Configuration for custom tools defined via JSON config.
 * Phase 4: Allows users to define tools without writing Java code.
 */
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
    public static class ToolParameter {
        private String name;
        private String description;
        private String type; // "string", "number", "boolean", "object", "array"
        private boolean required;
        private Object defaultValue;

        public ToolParameter() {
        }

        public ToolParameter(String name, String description, String type, boolean required, Object defaultValue) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.required = required;
            this.defaultValue = defaultValue;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ToolParameter that = (ToolParameter) o;
            return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(description, that.description) && java.util.Objects.equals(type, that.type) && required == that.required && java.util.Objects.equals(defaultValue, that.defaultValue);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, description, type, required, defaultValue);
        }

        @Override
        public String toString() {
            return "ToolParameter(" + "name=" + name + ", description=" + description + ", type=" + type + ", required=" + required + ", defaultValue=" + defaultValue + ")";
        }
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

    public CustomToolConfiguration() {
    }

    public CustomToolConfiguration(String name, String description, ToolType type, List<ToolParameter> parameters, Map<String, Object> config, boolean requiresAuth, double costPerExecution, Long cacheTtlMs, Integer rateLimit) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.parameters = parameters;
        this.config = config;
        this.requiresAuth = requiresAuth;
        this.costPerExecution = costPerExecution;
        this.cacheTtlMs = cacheTtlMs;
        this.rateLimit = rateLimit;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ToolType getType() {
        return type;
    }

    public void setType(ToolType type) {
        this.type = type;
    }

    public List<ToolParameter> getParameters() {
        return parameters;
    }

    public void setParameters(List<ToolParameter> parameters) {
        this.parameters = parameters;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public boolean isRequiresAuth() {
        return requiresAuth;
    }

    public void setRequiresAuth(boolean requiresAuth) {
        this.requiresAuth = requiresAuth;
    }

    public double getCostPerExecution() {
        return costPerExecution;
    }

    public void setCostPerExecution(double costPerExecution) {
        this.costPerExecution = costPerExecution;
    }

    public Long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(Long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }

    public Integer getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(Integer rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomToolConfiguration that = (CustomToolConfiguration) o;
        return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(description, that.description) && java.util.Objects.equals(type, that.type) && java.util.Objects.equals(parameters, that.parameters) && java.util.Objects.equals(config, that.config) && requiresAuth == that.requiresAuth && Double.compare(that.costPerExecution, costPerExecution) == 0 && java.util.Objects.equals(cacheTtlMs, that.cacheTtlMs) && java.util.Objects.equals(rateLimit, that.rateLimit);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, description, type, parameters, config, requiresAuth, costPerExecution, cacheTtlMs, rateLimit);
    }

    @Override
    public String toString() {
        return "CustomToolConfiguration(" + "name=" + name + ", description=" + description + ", type=" + type + ", parameters=" + parameters + ", config=" + config + ", requiresAuth=" + requiresAuth + ", costPerExecution=" + costPerExecution + ", cacheTtlMs=" + cacheTtlMs + ", rateLimit=" + rateLimit + ")";
    }
}

