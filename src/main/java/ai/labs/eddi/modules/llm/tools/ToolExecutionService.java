/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Wrapper for tool execution with caching, rate limiting, per-call timeouts and
 * cost tracking.
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
 *
 * <p>
 * The per-call execution timeout added later does <em>not</em> reopen that
 * door. It bounds the one supplier this class already runs, on the live
 * dispatch path, and it is still one call at a time: the pipeline thread simply
 * waits on a worker instead of running the tool itself, so it can stop waiting.
 * No {@code Method}, no instance, no argument array — nothing reflective is
 * involved, and the calls of a batch are no more concurrent than before.
 * </p>
 *
 * <p>
 * What a timeout does <em>not</em> do is stop the tool. The future is cancelled
 * with {@code mayInterruptIfRunning}, so a worker blocked in interruptible I/O
 * unwinds at once — but one stuck in a native call, a non-interruptible driver
 * or a tight loop keeps running, and it still holds the same
 * {@code IConversationMemory} the turn does. It can therefore still write to
 * conversation memory, minutes after the model was told the call failed, and
 * still fire whatever external side effect it had begun. That is the honest
 * cost of bounding a call in a language with no safe kill: the alternative on
 * offer is not "stop it cleanly", it is "wait forever". Tools whose side
 * effects must not be doubled belong behind the HITL tool-approval gate or a
 * per-tool {@code toolTimeoutsMs} entry of {@code -1}, not behind a shorter
 * timeout.
 * </p>
 *
 * <p>
 * Such workers are counted rather than assumed away. The gauge
 * {@code eddi.tool.execution.abandoned} reports how many are running that
 * nobody is waiting for; a number that climbs and does not come back down is a
 * tool leaking workers, and it is alertable before it is an outage. There is
 * deliberately no admission bound on {@link #timeoutExecutor}: refusing a
 * healthy tool call because unrelated calls are stuck would turn one tool's
 * hang into a conversation-wide failure, which is a strictly worse blast radius
 * than the one this class already accepts. If a deployment wants that trade,
 * the lever is {@code toolTimeoutsMs} on the offending tool.
 * </p>
 */
@ApplicationScoped
public class ToolExecutionService {
    private static final Logger LOGGER = Logger.getLogger(ToolExecutionService.class);

    /**
     * {@code timeoutMs} value meaning "no bound": run the tool inline on the
     * calling thread, exactly as this class did before timeouts existed.
     * <p>
     * Zero is treated identically — an operator writing {@code 0} means "off", not
     * "expire immediately", and the same convention already governs
     * {@code maxToolContextTokens}.
     */
    public static final int TIMEOUT_DISABLED = -1;

    /**
     * Where a time-bounded tool call runs.
     * <p>
     * One executor for the whole application, not one per call, and virtual threads
     * rather than a pool so that the abandoned worker of a timed-out call costs
     * (almost) nothing while it stays stuck: a parked virtual thread holds no
     * platform thread and no megabyte-sized stack, which is the difference between
     * a hung tool being an incident and being a log line. A field initializer
     * rather than {@code @PostConstruct} because unit tests construct this service
     * directly.
     */
    private final ExecutorService timeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Workers currently inside {@code toolExecution.get()}, and the subset of those
     * whose submitter is still waiting on them.
     *
     * <p>
     * The difference is the number of abandoned workers: tools that outlived their
     * bound, were interrupted, did not take the hint, and are still holding the
     * {@code IConversationMemory} of a turn that has long since moved on. That
     * number is the honest cost of bounding a call in a language with no safe kill,
     * and leaving it invisible is what would make it dangerous — an operator who
     * cannot see a leak cannot act on one. It is exported as
     * {@code eddi.tool.execution.abandoned} so they can.
     * </p>
     *
     * <p>
     * Both are incremented and decremented in {@code finally} blocks on the thread
     * that owns them, so neither can drift: a worker that is cancelled before it
     * ever runs counts in neither. The gauge can read a transient {@code 0} while a
     * just-submitted worker has not entered its body yet, which is why it clamps
     * rather than reporting a negative.
     * </p>
     */
    private final AtomicInteger runningWorkers = new AtomicInteger();

    private final AtomicInteger awaitedWorkers = new AtomicInteger();

    @Inject
    ToolCacheService cacheService;

    @Inject
    ToolRateLimiter rateLimiter;

    @Inject
    ToolCostTracker costTracker;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Carries the turn's caller (and resolution principal) onto the timeout worker.
     * <p>
     * Without it a time-bounded tool call would lose both bindings the moment it
     * left the pipeline thread, and a {@code ${caller:token}} header or a
     * {@code PER_USER} connection would fail closed for no reason an agent designer
     * could see — the exact drift {@link CallerIdentityContext#propagate} exists to
     * prevent. Null-tolerant: unit tests build this service by hand.
     */
    @Inject
    CallerIdentityContext callerIdentityContext;

    /**
     * Returns the cost tracker for budget checking.
     */
    public ToolCostTracker getCostTracker() {
        return costTracker;
    }

    @PostConstruct
    public void init() {
        // Every counter/timer below is per-tool (tagged "tool") and created lazily
        // on first use, so there is nothing to pre-register for those. The one
        // exception is the abandoned-worker gauge: a gauge has to be registered
        // before it can be sampled, and it is deployment-wide rather than per-tool
        // because what an operator needs to know is whether workers are piling up
        // at all, not which tool leaked the most of them — the per-tool
        // eddi.tool.execution.timeout counter already answers that.
        meterRegistry.gauge("eddi.tool.execution.abandoned", this, ToolExecutionService::abandonedWorkers);
        LOGGER.info("Tool execution service initialized");
    }

    /**
     * Workers still running that nobody is waiting for any more.
     *
     * <p>
     * Clamped at zero: the two counters are read one after the other rather than
     * under a lock, so a worker submitted between the two reads would otherwise
     * show up as {@code -1}. A gauge that dips negative is a gauge operators learn
     * to distrust.
     * </p>
     */
    double abandonedWorkers() {
        return Math.max(0, runningWorkers.get() - awaitedWorkers.get());
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
        // No execution timeout on this overload, and deliberately so: its one caller
        // is McpCallsTask, whose tools are bounded by the transport timeout in their
        // own configuration (McpCallsConfiguration#timeoutMs, 30s by default). The
        // per-tool timeout resolved below belongs to LlmConfiguration.Task, which a
        // mcpcalls.json workflow step never has.
        return executeToolWrapped(ToolInvocation.of(toolName), arguments, cacheScopeTag, conversationId, toolExecution,
                enableRateLimiting, enableCaching, enableCostTracking, rateLimit, TIMEOUT_DISABLED);
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
     * one shared allowance. Those buckets are additionally scoped to
     * {@code conversationId}, so the configured limit is per conversation rather
     * than deployment-wide.
     * </p>
     *
     * @param invocation
     *            the tool call, carrying dispatch name, canonical slug and any
     *            operator price override
     * @param timeoutMs
     *            wall-clock ceiling on the execution step, resolved from
     *            {@code toolTimeoutsMs}/{@code defaultToolTimeoutMs}.
     *            {@link #TIMEOUT_DISABLED} (or {@code 0}) runs the tool inline with
     *            no bound
     * @see #executeToolWrapped(String, String, String, String, Supplier, boolean,
     *      boolean, boolean, int)
     */
    public String executeToolWrapped(ToolInvocation invocation, String arguments, String cacheScopeTag, String conversationId,
                                     Supplier<String> toolExecution, boolean enableRateLimiting, boolean enableCaching,
                                     boolean enableCostTracking, int rateLimit, int timeoutMs) {

        String toolName = invocation.dispatchName();
        long startTime = System.currentTimeMillis();

        // Caching additionally requires a scope tag to partition the entry by. When
        // one cannot be resolved the cache is skipped on both the read and the write
        // side, so an unattributable result is neither served nor stored.
        // Stateful tools (artifacts, group tasks, dynamic agents, memory) are never
        // cached: their result changes with what peers do, and a cache hit would skip a
        // side effect. See ToolCacheService#isCacheable.
        boolean cacheable = enableCaching && cacheScopeTag != null && ToolCacheService.isCacheable(invocation);
        if (enableCaching && cacheScopeTag == null) {
            meterRegistry.counter("eddi.tool.cache.bypassed", "tool", toolName).increment();
        }

        try {
            // 1. Check rate limit — per conversation, so one conversation exhausting
            // its allowance cannot starve the same tool for every other user.
            if (enableRateLimiting && !rateLimiter.tryAcquire(conversationId, toolName, rateLimit)) {
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

            // 3. Execute tool, under the configured wall-clock ceiling.
            //
            // This is the step that had no bound at all: a tool that never returned
            // held the conversation turn open for as long as it felt like. The wait
            // is bounded here rather than in each of the seven tool sources because
            // this is the one place all of them pass through.
            String result = executeBounded(toolExecution, timeoutMs);

            // 4. Cache result (TTL from the canonical slug, key from the dispatch name)
            if (cacheable) {
                cacheService.put(cacheScopeTag, invocation, arguments, result);
            }

            // 5. Track cost (price from the canonical slug or the operator override).
            // Its own try: the tool has ALREADY run and its side effects are done, so
            // a tracking failure must not replace the result with an error — that
            // discarded real work and, since the result was already cached, a retry
            // was served from the cache without ever being charged.
            if (enableCostTracking && conversationId != null) {
                try {
                    costTracker.trackToolCall(invocation, conversationId);
                } catch (RuntimeException trackingFailure) {
                    LOGGER.errorf("Cost tracking failed for tool '%s'; the tool result is returned unchanged: %s", toolName,
                            trackingFailure.getMessage());
                }
            }

            long executionTime = System.currentTimeMillis() - startTime;
            meterRegistry.counter("eddi.tool.execution.success", "tool", toolName).increment();
            meterRegistry.timer("eddi.tool.execution.duration", "tool", toolName).record(executionTime, TimeUnit.MILLISECONDS);

            return result;

        } catch (ToolTimedOut timedOut) {
            // Deliberately NOT rethrown, and deliberately not a TimeoutException.
            //
            // RetryConfiguration.isRetryableError treats a TimeoutException anywhere
            // in the cause chain as retryable, so letting one escape this method would
            // put a hanging tool on a retry loop — three more full waits, and three
            // more chances to fire whatever side effect the tool had already started.
            // The model is told instead, in the same shape as the rate-limit branch
            // above, and gets to recover: apologise, try a different tool, or answer
            // without one.
            //
            // Nothing is cached and nothing is charged: both of those steps sit after
            // the execution step we just left.
            meterRegistry.counter("eddi.tool.execution.failure", "tool", toolName).increment();
            meterRegistry.counter("eddi.tool.execution.timeout", "tool", toolName).increment();
            LOGGER.warnf("Tool '%s' exceeded its %dms execution timeout and was abandoned; the model was told so.",
                    toolName, timedOut.timeoutMs);
            return "Error: Execution timed out after " + timedOut.timeoutMs + "ms for tool: " + toolName;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            meterRegistry.counter("eddi.tool.execution.failure", "tool", toolName).increment();

            LOGGER.error(String.format("Tool '%s' failed (%dms): %s", toolName, executionTime, error), e);
            return "Error executing tool: " + error;
        }
    }

    /**
     * Runs {@code toolExecution}, giving up after {@code timeoutMs}.
     *
     * <p>
     * With no bound configured the supplier runs inline on the calling thread,
     * byte-identical to what this class did before timeouts existed — no worker, no
     * context hand-off, nothing to get wrong on the path operators leave alone.
     * </p>
     *
     * <p>
     * With a bound, the supplier runs on {@link #timeoutExecutor} and the pipeline
     * thread waits on the future. On expiry the future is cancelled with
     * {@code mayInterruptIfRunning}, which interrupts the worker: a tool blocked in
     * interruptible I/O or a {@code sleep} unwinds immediately, and one spinning in
     * an uninterruptible call keeps running until it finishes on its own. See the
     * class-level note on why that residual case is survivable.
     * </p>
     *
     * <p>
     * Same shape as {@code CascadingModelExecutor#executeStepWithTimeout}, which
     * bounds a model cascade step: one virtual-thread executor, the caller bindings
     * carried across, {@code cancel(true)} on expiry, and the
     * {@code ExecutionException} unwrapped so the cause reaches its own handler.
     * Deliberately so — a second, differently-shaped way of timing work in the same
     * package is how one of them ends up missing a binding.
     * </p>
     *
     * @throws ToolTimedOut
     *             when the bound expires — caught by the caller and turned into a
     *             result for the model
     */
    private String executeBounded(Supplier<String> toolExecution, int timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            return toolExecution.get();
        }

        Future<String> future = timeoutExecutor.submit(countRunning(carryContext(toolExecution)));
        awaitedWorkers.incrementAndGet();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            future.cancel(true);
            // Constructed with no cause on purpose: a TimeoutException reachable
            // through getCause() would make this retryable again by the back door.
            throw new ToolTimedOut(timeoutMs);
        } catch (InterruptedException interrupted) {
            // The pipeline thread itself was cancelled (agent watchdog, abandoned
            // cascade step). Take the tool down with it and re-arm the flag so the
            // dispatch loop's own Thread.interrupted() check still sees it.
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            // Unwrap, so a tool's own failure reaches the generic handler with the
            // message it actually threw rather than "java.lang.RuntimeException: …"
            // wrapped one layer deeper than before this method existed.
            Throwable cause = failed.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failed;
        } finally {
            // Whatever the outcome, this thread has stopped waiting. A worker still
            // inside its body from here on is an abandoned one, which is exactly
            // what the gauge reports.
            awaitedWorkers.decrementAndGet();
        }
    }

    /**
     * Counts the worker while it is inside the tool, so {@link #abandonedWorkers()}
     * can tell a worker that is still running from one that has finished.
     *
     * <p>
     * The increment is inside the task body rather than at the submit site on
     * purpose: a task cancelled before it ever started would never reach its
     * {@code finally} and would leak a permanent {@code +1} into a gauge whose
     * whole point is to make a leak visible.
     * </p>
     */
    private Callable<String> countRunning(Callable<String> work) {
        return () -> {
            runningWorkers.incrementAndGet();
            try {
                return work.call();
            } finally {
                runningWorkers.decrementAndGet();
            }
        };
    }

    /**
     * Wraps the supplier so the worker thread keeps the bindings the turn has set
     * on the pipeline thread.
     *
     * <p>
     * Three of them travel:
     * </p>
     * <ul>
     * <li>the caller identity and the resolution principal, together, via
     * {@link CallerIdentityContext#propagate} — the single place that pairing is
     * maintained, so a {@code ${caller:token}} header and a {@code PER_USER}
     * connection resolve on the worker exactly as they would inline;</li>
     * <li>{@code EddiToolBridge}'s conversation id, which that tool reads from a
     * {@link ThreadLocal} rather than from its arguments;</li>
     * <li>the OpenTelemetry context, so spans a tool opens stay children of the
     * {@code eddi.pipeline.task} span instead of becoming roots.</li>
     * </ul>
     */
    private Callable<String> carryContext(Supplier<String> toolExecution) {
        final String bridgeConversationId = EddiToolBridge.currentConversationId();

        Callable<String> work = () -> {
            final String previous = EddiToolBridge.currentConversationId();
            EddiToolBridge.setCurrentConversationId(bridgeConversationId);
            try {
                return toolExecution.get();
            } finally {
                if (previous == null) {
                    EddiToolBridge.clearCurrentConversationId();
                } else {
                    EddiToolBridge.setCurrentConversationId(previous);
                }
            }
        };

        if (callerIdentityContext != null) {
            work = callerIdentityContext.propagate(work);
        }
        return Context.current().wrap(work);
    }

    /**
     * Stop the executor when the application does, so a shutdown is not held open
     * by a tool that was already hanging.
     */
    @PreDestroy
    void shutdown() {
        timeoutExecutor.shutdownNow();
    }

    /**
     * Internal signal that a tool call outlived its bound.
     *
     * <p>
     * Private, unchecked, stackless and <em>not</em> a
     * {@link java.util.concurrent.TimeoutException} — see the catch block that
     * consumes it. It never leaves this class.
     * </p>
     */
    private static final class ToolTimedOut extends RuntimeException {
        private final int timeoutMs;

        private ToolTimedOut(int timeoutMs) {
            super("tool execution exceeded " + timeoutMs + "ms", null, false, false);
            this.timeoutMs = timeoutMs;
        }
    }
}
