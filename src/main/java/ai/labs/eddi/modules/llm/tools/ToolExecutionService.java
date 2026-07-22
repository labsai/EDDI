/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Wrapper for tool execution with caching, rate limiting and cost tracking.
 *
 * <p>
 * The single entry point is {@link #executeToolWrapped}, called from
 * {@code AgentOrchestrator} once per tool call the model requests. It is
 * name-based on purpose: agent-mode tools are dispatched by langchain4j through
 * {@code ToolExecutor.execute(ToolExecutionRequest, memoryId)}, which yields a
 * {@code (name, jsonArguments)} pair — MCP, A2A and dynamic tools have no Java
 * {@code Method} behind them at all.
 * </p>
 *
 * <p>
 * Historical note: this class used to carry a second, reflection-based path
 * ({@code executeTool(Object, Method, Object[], …)} plus
 * {@code executeToolsParallel}/{@code executeToolsParallelAndWait} over a
 * ten-thread pool), switched on by the {@code enableParallelExecution} /
 * {@code parallelExecutionTimeoutMs} task config. Nothing in production ever
 * called it — the triple it needed cannot be produced from a
 * {@code ToolExecutionRequest} — so it was deleted rather than wired, together
 * with its {@code eddi.tool.execution.parallel*} meters, which could only ever
 * report zero. Re-introducing concurrent tool calls means batching at the
 * {@code AgentOrchestrator} dispatch loop, not resurrecting reflection.
 * </p>
 */
@ApplicationScoped
public class ToolExecutionService {
    private static final Logger LOGGER = Logger.getLogger(ToolExecutionService.class);

    @Inject
    ToolCacheService cacheService;

    @Inject
    ToolRateLimiter rateLimiter;

    @Inject
    ToolCostTracker costTracker;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Returns the cost tracker for budget checking.
     */
    public ToolCostTracker getCostTracker() {
        return costTracker;
    }

    @PostConstruct
    public void init() {
        // No meters are registered up front: every counter/timer below is per-tool
        // (tagged "tool") and created lazily on first use, so there is nothing to
        // pre-register here.
        LOGGER.info("Tool execution service initialized");
    }

    /**
     * Execute a tool with optional rate limiting, caching, and cost tracking. This
     * method wraps a tool execution supplier with the configured controls.
     *
     * @param toolName
     *            name of the tool being executed
     * @param arguments
     *            serialized arguments (part of the cache key)
     * @param cacheScopeTag
     *            identity partition for the cache key, from
     *            {@link ToolCacheService#resolveScopeTag}. {@code null} means no
     *            usable identity was available and the cache is bypassed entirely —
     *            never substitute a placeholder, that would collapse every
     *            anonymous caller back into one shared partition
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
    public String executeToolWrapped(String toolName, String arguments, String cacheScopeTag, String conversationId,
                                     Supplier<String> toolExecution, boolean enableRateLimiting, boolean enableCaching,
                                     boolean enableCostTracking, int rateLimit) {
        return executeToolWrapped(ToolInvocation.of(toolName), arguments, cacheScopeTag, conversationId, toolExecution,
                enableRateLimiting, enableCaching, enableCostTracking, rateLimit);
    }

    /**
     * Execute a tool with optional rate limiting, caching, and cost tracking.
     *
     * <p>
     * Identical to the overload above except that the call carries both of its
     * names (see {@link ToolInvocation}). Everything that identifies the
     * <em>call</em> — the rate limit bucket, the cache key, the metric tags, the
     * failure log — stays on the dispatch name; only the price and the cache TTL
     * are resolved from the canonical slug. Notably the rate-limit BUCKET is
     * per-dispatch-name even when the configured limit came from a slug, so
     * {@code {"websearch": 30}} yields three independent 30/min buckets for
     * {@code searchWeb}, {@code searchNews} and {@code searchWikipedia} rather than
     * one shared allowance.
     * </p>
     *
     * @param invocation
     *            the tool call, carrying dispatch name, canonical slug and any
     *            operator price override
     * @see #executeToolWrapped(String, String, String, String, Supplier, boolean,
     *      boolean, boolean, int)
     */
    public String executeToolWrapped(ToolInvocation invocation, String arguments, String cacheScopeTag, String conversationId,
                                     Supplier<String> toolExecution, boolean enableRateLimiting, boolean enableCaching,
                                     boolean enableCostTracking, int rateLimit) {

        String toolName = invocation.dispatchName();
        long startTime = System.currentTimeMillis();

        // Caching additionally requires a scope tag to partition the entry by. When
        // one cannot be resolved the cache is skipped on both the read and the write
        // side, so an unattributable result is neither served nor stored.
        boolean cacheable = enableCaching && cacheScopeTag != null;
        if (enableCaching && cacheScopeTag == null) {
            meterRegistry.counter("eddi.tool.cache.bypassed", "tool", toolName).increment();
        }

        try {
            // 1. Check rate limit
            if (enableRateLimiting && !rateLimiter.tryAcquire(toolName, rateLimit)) {
                meterRegistry.counter("eddi.tool.execution.failure", "tool", toolName).increment();
                meterRegistry.counter("eddi.tool.execution.ratelimited", "tool", toolName).increment();
                return "Error: Rate limit exceeded for tool: " + toolName;
            }

            // 2. Check cache
            if (cacheable) {
                String cachedResult = cacheService.get(cacheScopeTag, toolName, arguments);
                if (cachedResult != null) {
                    meterRegistry.counter("eddi.tool.execution.success", "tool", toolName).increment();
                    meterRegistry.counter("eddi.tool.execution.cached", "tool", toolName).increment();
                    return cachedResult;
                }
            }

            // 3. Execute tool
            String result = toolExecution.get();

            // 4. Cache result (TTL from the canonical slug, key from the dispatch name)
            if (cacheable) {
                cacheService.put(cacheScopeTag, invocation, arguments, result);
            }

            // 5. Track cost (price from the canonical slug or the operator override)
            if (enableCostTracking && conversationId != null) {
                costTracker.trackToolCall(invocation, conversationId);
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
}
