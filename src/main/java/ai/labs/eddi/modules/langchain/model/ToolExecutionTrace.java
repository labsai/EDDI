package ai.labs.eddi.modules.langchain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks tool calls made during agent execution for debugging, logging, and analytics.
 * Phase 4: Enhanced with metrics, caching, and cost tracking.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionTrace {

    /**
     * List of individual tool calls made during this agent turn
     */
    private List<ToolCall> toolCalls = new ArrayList<>();

    /**
     * Total execution time for all tool calls in milliseconds
     */
    private long totalExecutionTimeMs;

    /**
     * Whether any tool calls failed
     */
    private boolean hasErrors;

    /**
     * Total cost of all tool calls (in credits/dollars)
     */
    private double totalCost;

    /**
     * Number of cache hits
     */
    private int cacheHits;

    /**
     * Number of cache misses
     */
    private int cacheMisses;

    /**
     * Tool execution metrics
     */
    private Map<String, ToolMetrics> toolMetrics = new HashMap<>();

    /**
     * Individual tool call record
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        /**
         * Name of the tool that was called
         */
        private String toolName;

        /**
         * Arguments passed to the tool (as JSON string)
         */
        private String arguments;

        /**
         * Result returned by the tool
         */
        private String result;

        /**
         * Execution time in milliseconds
         */
        private long executionTimeMs;

        /**
         * Error message if the tool call failed
         */
        private String error;

        /**
         * Whether this tool call succeeded
         */
        private boolean success;

        /**
         * Cost of this tool call (API costs, etc.)
         */
        private double cost;

        /**
         * Whether result was served from cache
         */
        private boolean fromCache;

        /**
         * Timestamp when tool was called
         */
        private long timestamp;
    }

    /**
     * Metrics for a specific tool
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolMetrics {
        private String toolName;
        private int totalCalls;
        private int successfulCalls;
        private int failedCalls;
        private long totalExecutionTimeMs;
        private long minExecutionTimeMs;
        private long maxExecutionTimeMs;
        private double totalCost;
        private int cacheHits;

        public double getSuccessRate() {
            return totalCalls > 0 ? (double) successfulCalls / totalCalls * 100 : 0;
        }

        public double getAverageExecutionTimeMs() {
            return totalCalls > 0 ? (double) totalExecutionTimeMs / totalCalls : 0;
        }

        public double getCacheHitRate() {
            return totalCalls > 0 ? (double) cacheHits / totalCalls * 100 : 0;
        }
    }

    /**
     * Add a successful tool call to the trace
     */
    public void addToolCall(String toolName, String arguments, String result, long executionTimeMs,
                           double cost, boolean fromCache) {
        ToolCall call = new ToolCall();
        call.setToolName(toolName);
        call.setArguments(arguments);
        call.setResult(result);
        call.setExecutionTimeMs(executionTimeMs);
        call.setCost(cost);
        call.setFromCache(fromCache);
        call.setSuccess(true);
        call.setTimestamp(System.currentTimeMillis());

        toolCalls.add(call);
        totalExecutionTimeMs += executionTimeMs;
        totalCost += cost;

        if (fromCache) {
            cacheHits++;
        } else {
            cacheMisses++;
        }

        updateMetrics(toolName, executionTimeMs, cost, true, fromCache);
    }

    /**
     * Add a failed tool call to the trace
     */
    public void addFailedToolCall(String toolName, String arguments, String error, long executionTimeMs, double cost) {
        ToolCall call = new ToolCall();
        call.setToolName(toolName);
        call.setArguments(arguments);
        call.setError(error);
        call.setExecutionTimeMs(executionTimeMs);
        call.setCost(cost);
        call.setSuccess(false);
        call.setFromCache(false);
        call.setTimestamp(System.currentTimeMillis());

        toolCalls.add(call);
        totalExecutionTimeMs += executionTimeMs;
        totalCost += cost;
        hasErrors = true;
        cacheMisses++;

        updateMetrics(toolName, executionTimeMs, cost, false, false);
    }

    /**
     * Update metrics for a specific tool
     */
    private void updateMetrics(String toolName, long executionTimeMs, double cost, boolean success, boolean fromCache) {
        ToolMetrics metrics = toolMetrics.computeIfAbsent(toolName, k -> {
            ToolMetrics m = new ToolMetrics();
            m.setToolName(toolName);
            m.setMinExecutionTimeMs(Long.MAX_VALUE);
            m.setMaxExecutionTimeMs(0);
            return m;
        });

        metrics.setTotalCalls(metrics.getTotalCalls() + 1);
        if (success) {
            metrics.setSuccessfulCalls(metrics.getSuccessfulCalls() + 1);
        } else {
            metrics.setFailedCalls(metrics.getFailedCalls() + 1);
        }

        metrics.setTotalExecutionTimeMs(metrics.getTotalExecutionTimeMs() + executionTimeMs);
        metrics.setMinExecutionTimeMs(Math.min(metrics.getMinExecutionTimeMs(), executionTimeMs));
        metrics.setMaxExecutionTimeMs(Math.max(metrics.getMaxExecutionTimeMs(), executionTimeMs));
        metrics.setTotalCost(metrics.getTotalCost() + cost);

        if (fromCache) {
            metrics.setCacheHits(metrics.getCacheHits() + 1);
        }
    }

    /**
     * Get summary of tool execution
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool Execution Summary:\n");
        sb.append("Total Calls: ").append(toolCalls.size()).append("\n");
        sb.append("Total Time: ").append(totalExecutionTimeMs).append("ms\n");
        sb.append("Total Cost: $").append(String.format("%.4f", totalCost)).append("\n");
        sb.append("Cache Hit Rate: ").append(
            toolCalls.size() > 0 ? String.format("%.1f%%", (double) cacheHits / toolCalls.size() * 100) : "0%"
        ).append("\n");
        sb.append("Errors: ").append(hasErrors ? "Yes" : "No").append("\n");

        if (!toolMetrics.isEmpty()) {
            sb.append("\nPer-Tool Metrics:\n");
            toolMetrics.values().forEach(m -> {
                sb.append("  - ").append(m.getToolName()).append(": ")
                  .append(m.getTotalCalls()).append(" calls, ")
                  .append(String.format("%.1f%%", m.getSuccessRate())).append(" success, ")
                  .append(String.format("%.0f", m.getAverageExecutionTimeMs())).append("ms avg\n");
            });
        }

        return sb.toString();
    }
}

