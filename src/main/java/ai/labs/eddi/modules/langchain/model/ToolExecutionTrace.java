package ai.labs.eddi.modules.langchain.model;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks tool calls made during agent execution for debugging, logging, and analytics.
 * Phase 4: Enhanced with metrics, caching, and cost tracking.
 */
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

        public ToolCall() {
        }

        public ToolCall(String toolName, String arguments, String result, long executionTimeMs, String error, boolean success, double cost, boolean fromCache, long timestamp) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.result = result;
            this.executionTimeMs = executionTimeMs;
            this.error = error;
            this.success = success;
            this.cost = cost;
            this.fromCache = fromCache;
            this.timestamp = timestamp;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public long getExecutionTimeMs() {
            return executionTimeMs;
        }

        public void setExecutionTimeMs(long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public double getCost() {
            return cost;
        }

        public void setCost(double cost) {
            this.cost = cost;
        }

        public boolean isFromCache() {
            return fromCache;
        }

        public void setFromCache(boolean fromCache) {
            this.fromCache = fromCache;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ToolCall that = (ToolCall) o;
            return java.util.Objects.equals(toolName, that.toolName) && java.util.Objects.equals(arguments, that.arguments) && java.util.Objects.equals(result, that.result) && executionTimeMs == that.executionTimeMs && java.util.Objects.equals(error, that.error) && success == that.success && Double.compare(that.cost, cost) == 0 && fromCache == that.fromCache && timestamp == that.timestamp;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(toolName, arguments, result, executionTimeMs, error, success, cost, fromCache, timestamp);
        }

        @Override
        public String toString() {
            return "ToolCall(" + "toolName=" + toolName + ", arguments=" + arguments + ", result=" + result + ", executionTimeMs=" + executionTimeMs + ", error=" + error + ", success=" + success + ", cost=" + cost + ", fromCache=" + fromCache + ", timestamp=" + timestamp" + ")";
        }
    }

    /**
     * Metrics for a specific tool
     */
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

        public ToolMetrics() {
        }

        public ToolMetrics(String toolName, int totalCalls, int successfulCalls, int failedCalls, long totalExecutionTimeMs, long minExecutionTimeMs, long maxExecutionTimeMs, double totalCost, int cacheHits) {
            this.toolName = toolName;
            this.totalCalls = totalCalls;
            this.successfulCalls = successfulCalls;
            this.failedCalls = failedCalls;
            this.totalExecutionTimeMs = totalExecutionTimeMs;
            this.minExecutionTimeMs = minExecutionTimeMs;
            this.maxExecutionTimeMs = maxExecutionTimeMs;
            this.totalCost = totalCost;
            this.cacheHits = cacheHits;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public int getTotalCalls() {
            return totalCalls;
        }

        public void setTotalCalls(int totalCalls) {
            this.totalCalls = totalCalls;
        }

        public int getSuccessfulCalls() {
            return successfulCalls;
        }

        public void setSuccessfulCalls(int successfulCalls) {
            this.successfulCalls = successfulCalls;
        }

        public int getFailedCalls() {
            return failedCalls;
        }

        public void setFailedCalls(int failedCalls) {
            this.failedCalls = failedCalls;
        }

        public long getTotalExecutionTimeMs() {
            return totalExecutionTimeMs;
        }

        public void setTotalExecutionTimeMs(long totalExecutionTimeMs) {
            this.totalExecutionTimeMs = totalExecutionTimeMs;
        }

        public long getMinExecutionTimeMs() {
            return minExecutionTimeMs;
        }

        public void setMinExecutionTimeMs(long minExecutionTimeMs) {
            this.minExecutionTimeMs = minExecutionTimeMs;
        }

        public long getMaxExecutionTimeMs() {
            return maxExecutionTimeMs;
        }

        public void setMaxExecutionTimeMs(long maxExecutionTimeMs) {
            this.maxExecutionTimeMs = maxExecutionTimeMs;
        }

        public double getTotalCost() {
            return totalCost;
        }

        public void setTotalCost(double totalCost) {
            this.totalCost = totalCost;
        }

        public int getCacheHits() {
            return cacheHits;
        }

        public void setCacheHits(int cacheHits) {
            this.cacheHits = cacheHits;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ToolMetrics that = (ToolMetrics) o;
            return java.util.Objects.equals(toolName, that.toolName) && totalCalls == that.totalCalls && successfulCalls == that.successfulCalls && failedCalls == that.failedCalls && totalExecutionTimeMs == that.totalExecutionTimeMs && minExecutionTimeMs == that.minExecutionTimeMs && maxExecutionTimeMs == that.maxExecutionTimeMs && Double.compare(that.totalCost, totalCost) == 0 && cacheHits == that.cacheHits;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(toolName, totalCalls, successfulCalls, failedCalls, totalExecutionTimeMs, minExecutionTimeMs, maxExecutionTimeMs, totalCost, cacheHits);
        }

        @Override
        public String toString() {
            return "ToolMetrics(" + "toolName=" + toolName + ", totalCalls=" + totalCalls + ", successfulCalls=" + successfulCalls + ", failedCalls=" + failedCalls + ", totalExecutionTimeMs=" + totalExecutionTimeMs + ", minExecutionTimeMs=" + minExecutionTimeMs + ", maxExecutionTimeMs=" + maxExecutionTimeMs + ", totalCost=" + totalCost + ", cacheHits=" + cacheHits" + ")";
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

    public ToolExecutionTrace() {
    }

    public ToolExecutionTrace(List<ToolCall> toolCalls, long totalExecutionTimeMs, boolean hasErrors, double totalCost, int cacheHits, int cacheMisses, Map<String, ToolMetrics> toolMetrics, ToolCall call, ToolCall call, StringBuilder sb) {
        this.toolCalls = toolCalls;
        this.totalExecutionTimeMs = totalExecutionTimeMs;
        this.hasErrors = hasErrors;
        this.totalCost = totalCost;
        this.cacheHits = cacheHits;
        this.cacheMisses = cacheMisses;
        this.toolMetrics = toolMetrics;
        this.call = call;
        this.call = call;
        this.sb = sb;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public void setTotalExecutionTimeMs(long totalExecutionTimeMs) {
        this.totalExecutionTimeMs = totalExecutionTimeMs;
    }

    public boolean isHasErrors() {
        return hasErrors;
    }

    public void setHasErrors(boolean hasErrors) {
        this.hasErrors = hasErrors;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public void setCacheHits(int cacheHits) {
        this.cacheHits = cacheHits;
    }

    public int getCacheMisses() {
        return cacheMisses;
    }

    public void setCacheMisses(int cacheMisses) {
        this.cacheMisses = cacheMisses;
    }

    public Map<String, ToolMetrics> getToolMetrics() {
        return toolMetrics;
    }

    public void setToolMetrics(Map<String, ToolMetrics> toolMetrics) {
        this.toolMetrics = toolMetrics;
    }

    public ToolCall getCall() {
        return call;
    }

    public void setCall(ToolCall call) {
        this.call = call;
    }

    public ToolCall getCall() {
        return call;
    }

    public void setCall(ToolCall call) {
        this.call = call;
    }

    public StringBuilder getSb() {
        return sb;
    }

    public void setSb(StringBuilder sb) {
        this.sb = sb;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolExecutionTrace that = (ToolExecutionTrace) o;
        return java.util.Objects.equals(toolCalls, that.toolCalls) && totalExecutionTimeMs == that.totalExecutionTimeMs && hasErrors == that.hasErrors && Double.compare(that.totalCost, totalCost) == 0 && cacheHits == that.cacheHits && cacheMisses == that.cacheMisses && java.util.Objects.equals(toolMetrics, that.toolMetrics) && java.util.Objects.equals(call, that.call) && java.util.Objects.equals(call, that.call) && java.util.Objects.equals(sb, that.sb);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(toolCalls, totalExecutionTimeMs, hasErrors, totalCost, cacheHits, cacheMisses, toolMetrics, call, call, sb);
    }

    @Override
    public String toString() {
        return "ToolExecutionTrace(" + "toolCalls=" + toolCalls + ", totalExecutionTimeMs=" + totalExecutionTimeMs + ", hasErrors=" + hasErrors + ", totalCost=" + totalCost + ", cacheHits=" + cacheHits + ", cacheMisses=" + cacheMisses + ", toolMetrics=" + toolMetrics + ", call=" + call + ", call=" + call + ", sb=" + sb" + ")";
    }
}

