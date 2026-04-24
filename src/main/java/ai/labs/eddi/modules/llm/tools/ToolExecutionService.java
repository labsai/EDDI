/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.model.ToolExecutionTrace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Wrapper for tool execution with caching, rate limiting, cost tracking, and
 * parallel execution. Phase 4: Complete tool execution orchestration with
 * metrics.
 */
@ApplicationScoped
public class ToolExecutionService {
    private static final Logger LOGGER = Logger.getLogger(ToolExecutionService.class);

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Inject
    ToolCacheService cacheService;

    @Inject
    ToolRateLimiter rateLimiter;

    @Inject
    ToolCostTracker costTracker;

    @Inject
    IJsonSerialization jsonSerialization;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Returns the cost tracker for budget checking.
     */
    public ToolCostTracker getCostTracker() {
        return costTracker;
    }

    // Metrics — all per-tool counters/timers use the "tool" tag for Prometheus
    // compatibility.
    // Aggregate totals can be computed via sum() in PromQL.
    private Counter parallelExecutionCounter;

    @PostConstruct
    public void init() {
        this.parallelExecutionCounter = meterRegistry.counter("eddi.tool.execution.parallel");

        LOGGER.info("Tool execution service initialized with metrics");
    }

    /**
     * Execute a tool with all Phase 4 features
     */
    public String executeTool(Object toolInstance, Method method, Object[] args, String conversationId, ToolExecutionTrace trace) {
        String toolName = method.getDeclaringClass().getSimpleName();
        String arguments = serializeArguments(args);
        long startTime = System.currentTimeMillis();

        try {
            // 1. Check rate limit
            if (!rateLimiter.tryAcquire(toolName)) {
                String error = "Rate limit exceeded for tool: " + toolName;
                long executionTime = System.currentTimeMillis() - startTime;
                trace.addFailedToolCall(toolName, arguments, error, executionTime, 0.0);

                meterRegistry.counter("eddi.tool.execution.failure", "tool", toolName).increment();
                meterRegistry.counter("eddi.tool.execution.ratelimited", "tool", toolName).increment();

                return "Error: " + error;
            }

            // 2. Check cache
            String cachedResult = cacheService.get(toolName, arguments);
            if (cachedResult != null) {
                long executionTime = System.currentTimeMillis() - startTime;
                double cost = 0.0; // No cost for cached results
                trace.addToolCall(toolName, arguments, cachedResult, executionTime, cost, true);

                meterRegistry.counter("eddi.tool.execution.success", "tool", toolName).increment();
                meterRegistry.counter("eddi.tool.execution.cached", "tool", toolName).increment();

                LOGGER.info(String.format("Tool '%s' served from cache (%dms)", toolName, executionTime));
                return cachedResult;
            }

            // 3. Track cost
            double cost = costTracker.trackToolCall(toolName, conversationId);

            // 4. Execute tool
            Object result = method.invoke(toolInstance, args);
            String resultString = result != null ? result.toString() : "null";

            long executionTime = System.currentTimeMillis() - startTime;

            // 5. Cache result
            cacheService.put(toolName, arguments, resultString);

            // 6. Update trace
            trace.addToolCall(toolName, arguments, resultString, executionTime, cost, false);

            // 7. Record success metrics
            meterRegistry.counter("eddi.tool.execution.success", "tool", toolName).increment();
            meterRegistry.timer("eddi.tool.execution.duration", "tool", toolName).record(executionTime, TimeUnit.MILLISECONDS);

            LOGGER.info(String.format("Tool '%s' executed successfully (%dms, $%.4f)", toolName, executionTime, cost));

            return resultString;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            // Cost was already tracked in step 3 above (if we got past rate limit and
            // cache)
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            trace.addFailedToolCall(toolName, arguments, error, executionTime, 0.0);

            // Record failure metrics
            meterRegistry.counter("eddi.tool.execution.failure", "tool", toolName).increment();

            LOGGER.error(String.format("Tool '%s' failed (%dms): %s", toolName, executionTime, error), e);

            return "Error executing tool: " + error;
        }
    }

    /**
     * Execute a tool with optional rate limiting, caching, and cost tracking. This
     * method wraps a tool execution supplier with the configured controls.
     *
     * @param toolName
     *            name of the tool being executed
     * @param arguments
     *            serialized arguments (used as cache key)
     * @param conversationId
     *            conversation ID for cost tracking
     * @param toolExecution
     *            the actual tool execution logic
     * @param enableRateLimiting
     *            whether to check rate limits
     * @param enableCaching
     *            whether to check/store cache
     * @param enableCostTracking
     *            whether to track costs
     * @param rateLimit
     *            rate limit (calls per minute)
     * @return the tool execution result
     */
    public String executeToolWrapped(String toolName, String arguments, String conversationId, Supplier<String> toolExecution,
                                     boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking, int rateLimit) {

        long startTime = System.currentTimeMillis();

        try {
            // 1. Check rate limit
            if (enableRateLimiting && !rateLimiter.tryAcquire(toolName, rateLimit)) {
                meterRegistry.counter("eddi.tool.execution.failure", "tool", toolName).increment();
                meterRegistry.counter("eddi.tool.execution.ratelimited", "tool", toolName).increment();
                return "Error: Rate limit exceeded for tool: " + toolName;
            }

            // 2. Check cache
            if (enableCaching) {
                String cachedResult = cacheService.get(toolName, arguments);
                if (cachedResult != null) {
                    meterRegistry.counter("eddi.tool.execution.success", "tool", toolName).increment();
                    meterRegistry.counter("eddi.tool.execution.cached", "tool", toolName).increment();
                    return cachedResult;
                }
            }

            // 3. Execute tool
            String result = toolExecution.get();

            // 4. Cache result
            if (enableCaching) {
                cacheService.put(toolName, arguments, result);
            }

            // 5. Track cost
            if (enableCostTracking && conversationId != null) {
                costTracker.trackToolCall(toolName, conversationId);
            }

            long executionTime = System.currentTimeMillis() - startTime;
            meterRegistry.counter("eddi.tool.execution.success", "tool", toolName).increment();
            meterRegistry.timer("eddi.tool.execution.duration", "tool", toolName).record(executionTime, TimeUnit.MILLISECONDS);

            return result;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            meterRegistry.counter("eddi.tool.execution.failure", "tool", toolName).increment();

            LOGGER.error(String.format("Tool '%s' failed (%dms): %s", toolName, executionTime, error), e);
            return "Error executing tool: " + error;
        }
    }

    /**
     * Execute multiple tools in parallel
     */
    public CompletableFuture<String>[] executeToolsParallel(Object[] toolInstances, Method[] methods, Object[][] args, String conversationId,
                                                            ToolExecutionTrace trace) {

        if (toolInstances.length != methods.length || methods.length != args.length) {
            throw new IllegalArgumentException("Arrays must have same length");
        }

        // Record parallel execution
        parallelExecutionCounter.increment();
        meterRegistry.counter("eddi.tool.execution.parallel.count").increment(toolInstances.length);

        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] futures = new CompletableFuture[toolInstances.length];

        for (int i = 0; i < toolInstances.length; i++) {
            final int index = i;
            futures[i] = CompletableFuture.supplyAsync(() -> executeTool(toolInstances[index], methods[index], args[index], conversationId, trace),
                    executorService);
        }

        LOGGER.info(String.format("Executing %d tools in parallel", toolInstances.length));

        return futures;
    }

    /**
     * Execute tools in parallel and wait for all to complete
     */
    public String[] executeToolsParallelAndWait(Object[] toolInstances, Method[] methods, Object[][] args, String conversationId,
                                                ToolExecutionTrace trace, long timeoutMs) {

        long startTime = System.currentTimeMillis();

        CompletableFuture<String>[] futures = executeToolsParallel(toolInstances, methods, args, conversationId, trace);

        try {
            CompletableFuture.allOf(futures).get(timeoutMs, TimeUnit.MILLISECONDS);

            String[] results = new String[futures.length];
            for (int i = 0; i < futures.length; i++) {
                results[i] = futures[i].get();
            }

            long executionTime = System.currentTimeMillis() - startTime;
            meterRegistry.timer("eddi.tool.execution.parallel.duration").record(executionTime, TimeUnit.MILLISECONDS);

            return results;

        } catch (TimeoutException e) {
            meterRegistry.counter("eddi.tool.execution.parallel.timeout").increment();
            LOGGER.error("Parallel tool execution timeout after " + timeoutMs + "ms");
            return new String[0];
        } catch (Exception e) {
            meterRegistry.counter("eddi.tool.execution.parallel.error").increment();
            LOGGER.error("Parallel tool execution error", e);
            return new String[0];
        }
    }

    /**
     * Serialize method arguments to string for caching
     */
    private String serializeArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }

        try {
            return jsonSerialization.serialize(args);
        } catch (Exception e) {
            // Fallback to simple toString
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < args.length; i++) {
                if (i > 0)
                    sb.append(", ");
                sb.append(args[i] != null ? args[i].toString() : "null");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * Shutdown executor service
     */
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
