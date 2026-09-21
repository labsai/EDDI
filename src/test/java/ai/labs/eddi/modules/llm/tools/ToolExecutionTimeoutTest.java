/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * The per-tool execution timeout inside
 * {@link ToolExecutionService#executeToolWrapped}.
 *
 * <p>
 * Step 3 of that pipeline used to be a bare {@code toolExecution.get()} with no
 * time bound of any kind, so a tool that never returned held the conversation
 * turn open indefinitely. These tests pin the bound and — just as importantly —
 * what it does <em>not</em> do: it does not fail the turn, it does not throw,
 * and it does not run the tool twice.
 * </p>
 */
@DisplayName("ToolExecutionService per-tool execution timeout")
class ToolExecutionTimeoutTest {

    /**
     * Stand-in scope tag; the resolution table is tested in ToolCacheServiceTest.
     */
    private static final String SCOPE = "u:0123456789abcdef0123456789abcdef";

    /**
     * Short enough to keep the suite quick, long enough not to be flaky on a loaded
     * machine.
     */
    private static final int SHORT_TIMEOUT_MS = 150;

    /**
     * Longer than any test is willing to wait for; every such supplier is
     * interruptible.
     */
    private static final long FOREVER_MS = 30_000;

    private ToolExecutionService service;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private ToolCacheService cacheService;

    @Mock
    private ToolRateLimiter rateLimiter;

    @Mock
    private ToolCostTracker costTracker;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        service = new ToolExecutionService();
        meterRegistry = new SimpleMeterRegistry();

        setField(service, "cacheService", cacheService);
        setField(service, "rateLimiter", rateLimiter);
        setField(service, "costTracker", costTracker);
        setField(service, "meterRegistry", meterRegistry);

        service.init();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** A supplier that blocks until interrupted, counting its own invocations. */
    private static Supplier<String> hangs(AtomicInteger attempts, CountDownLatch interrupted) {
        return () -> {
            attempts.incrementAndGet();
            try {
                Thread.sleep(FOREVER_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
            return "the tool eventually finished";
        };
    }

    private String runBounded(String toolName, Supplier<String> toolExecution, int timeoutMs) {
        return service.executeToolWrapped(ToolInvocation.of(toolName), "args", SCOPE, "conv-1",
                toolExecution, false, false, false, 60, timeoutMs);
    }

    // ==================== expiry ====================

    @Test
    @DisplayName("a tool that outlives its bound gives the MODEL an error result, not a failed turn")
    void timedOutToolIsAnsweredWithAnErrorResult() {
        var attempts = new AtomicInteger();
        var interrupted = new CountDownLatch(1);

        String result = runBounded("hangingTool", hangs(attempts, interrupted), SHORT_TIMEOUT_MS);

        assertEquals("Error: Execution timed out after " + SHORT_TIMEOUT_MS + "ms for tool: hangingTool", result,
                "the model must be handed a readable result it can recover from, in the same shape as the "
                        + "rate-limit branch — a thrown exception would end the whole turn instead");
    }

    /**
     * The retry trap. {@code RetryConfiguration.isRetryableError} walks the cause
     * chain and calls any {@link java.util.concurrent.TimeoutException} retryable,
     * so a timeout allowed to escape this method as an exception would put a
     * hanging tool on a retry loop: several more full waits, and several more
     * chances to fire a side effect the tool had already started. Returning a
     * string keeps it out of that loop entirely.
     */
    @Test
    @DisplayName("a timed-out tool is executed exactly ONCE — a timeout is never retried")
    void timedOutToolRunsExactlyOnce() throws Exception {
        var attempts = new AtomicInteger();
        var interrupted = new CountDownLatch(1);

        String result = assertDoesNotThrow(() -> runBounded("hangingTool", hangs(attempts, interrupted), SHORT_TIMEOUT_MS),
                "nothing may propagate out of executeToolWrapped on a timeout — least of all a TimeoutException, "
                        + "which RetryConfiguration.isRetryableError would treat as retryable");

        assertTrue(result.startsWith("Error: Execution timed out"), result);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the abandoned worker should have been interrupted");
        // Give any (incorrect) retry a chance to show up before counting.
        Thread.sleep(200);
        assertEquals(1, attempts.get(), "the tool must have been invoked exactly once");
    }

    @Test
    @DisplayName("expiry counts an eddi.tool.execution.timeout AND an eddi.tool.execution.failure, tagged by tool")
    void timeoutIncrementsItsOwnCounterAndTheFailureCounter() {
        var attempts = new AtomicInteger();
        var interrupted = new CountDownLatch(1);

        runBounded("hangingTool", hangs(attempts, interrupted), SHORT_TIMEOUT_MS);

        var timeouts = meterRegistry.find("eddi.tool.execution.timeout").tag("tool", "hangingTool").counter();
        assertNotNull(timeouts, "a dedicated timeout counter is what makes a hanging tool visible on a dashboard");
        assertEquals(1.0, timeouts.count());

        var failures = meterRegistry.find("eddi.tool.execution.failure").tag("tool", "hangingTool").counter();
        assertNotNull(failures, "a timeout is also a failure, exactly as a rate-limited call is");
        assertEquals(1.0, failures.count());

        assertNull(meterRegistry.find("eddi.tool.execution.success").tag("tool", "hangingTool").counter(),
                "an abandoned call is not a success");
    }

    @Test
    @DisplayName("an abandoned call is neither cached nor charged for")
    void timedOutCallIsNotCachedAndNotCharged() {
        var attempts = new AtomicInteger();
        var interrupted = new CountDownLatch(1);
        when(cacheService.get(SCOPE, "hangingTool", "args")).thenReturn(null);

        String result = service.executeToolWrapped(ToolInvocation.of("hangingTool"), "args", SCOPE, "conv-1",
                hangs(attempts, interrupted), false, true, true, 60, SHORT_TIMEOUT_MS);

        assertTrue(result.startsWith("Error: Execution timed out"), result);
        verify(cacheService, never()).put(anyString(), any(ToolInvocation.class), anyString(), anyString());
        verify(costTracker, never()).trackToolCall(any(ToolInvocation.class), anyString());
    }

    @Test
    @DisplayName("expiry cancels the future, interrupting the worker so an interruptible tool unwinds")
    void expiryInterruptsTheWorker() throws Exception {
        var attempts = new AtomicInteger();
        var interrupted = new CountDownLatch(1);

        runBounded("hangingTool", hangs(attempts, interrupted), SHORT_TIMEOUT_MS);

        assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                "cancel(true) must reach the worker; without it the thread leaks for the full sleep");
    }

    // ==================== the ordinary path ====================

    @Test
    @DisplayName("a tool that finishes inside its bound returns normally and is counted as a success")
    void fastToolIsUnaffected() {
        String result = runBounded("fastTool", () -> "42", SHORT_TIMEOUT_MS);

        assertEquals("42", result);
        assertNotNull(meterRegistry.find("eddi.tool.execution.success").tag("tool", "fastTool").counter());
        assertNull(meterRegistry.find("eddi.tool.execution.timeout").tag("tool", "fastTool").counter(),
                "no timeout may be counted for a call that finished in time");
    }

    /**
     * The worker hands failures back inside an {@code ExecutionException}. If that
     * wrapper were not unwrapped, every ordinary tool failure would start reporting
     * itself to the model as {@code java.lang.RuntimeException} instead of saying
     * what went wrong.
     */
    @Test
    @DisplayName("a tool that throws under a bound still reports its OWN message, not the executor's wrapper")
    void toolFailureKeepsItsMessage() {
        String result = runBounded("failingTool", () -> {
            throw new IllegalStateException("upstream said no");
        }, SHORT_TIMEOUT_MS);

        assertEquals("Error executing tool: upstream said no", result);
        assertNull(meterRegistry.find("eddi.tool.execution.timeout").tag("tool", "failingTool").counter(),
                "a plain failure is not a timeout");
    }

    // ==================== disabled ====================

    @Test
    @DisplayName("timeout -1 and 0 both run the tool INLINE on the calling thread, exactly as before the feature")
    void disabledTimeoutRunsInline() {
        var ranOn = new AtomicReference<Thread>();
        Supplier<String> recordsItsThread = () -> {
            ranOn.set(Thread.currentThread());
            return "ok";
        };

        assertEquals("ok", runBounded("inlineTool", recordsItsThread, ToolExecutionService.TIMEOUT_DISABLED));
        assertSame(Thread.currentThread(), ranOn.get(),
                "with no bound configured there must be no hand-off at all — the pre-timeout behaviour, byte for byte");

        ranOn.set(null);
        assertEquals("ok", runBounded("inlineTool", recordsItsThread, 0));
        assertSame(Thread.currentThread(), ranOn.get(),
                "0 means 'off' like -1, never 'expire immediately'");
    }

    @Test
    @DisplayName("the String overload (mcpcalls) is unbounded: it runs inline")
    void stringOverloadIsUnbounded() {
        var ranOn = new AtomicReference<Thread>();

        String result = service.executeToolWrapped("mcpTool", "args", SCOPE, "conv-1", () -> {
            ranOn.set(Thread.currentThread());
            return "ok";
        }, false, false, false, 60);

        assertEquals("ok", result);
        assertSame(Thread.currentThread(), ranOn.get(),
                "McpCallsTask bounds its tools through McpCallsConfiguration.timeoutMs; it has no LlmConfiguration.Task");
    }

    // ==================== resources ====================

    @Test
    @DisplayName("a bounded call runs on a worker, and every call shares ONE executor")
    void oneExecutorForEveryCall() throws Exception {
        var ranOn = new AtomicReference<Thread>();
        runBounded("boundedTool", () -> {
            ranOn.set(Thread.currentThread());
            return "ok";
        }, 10_000);
        assertNotNull(ranOn.get());
        assertTrue(ranOn.get() != Thread.currentThread(),
                "a bounded call must be waitable, which means it cannot run on the thread doing the waiting");

        Field field = ToolExecutionService.class.getDeclaredField("timeoutExecutor");
        field.setAccessible(true);
        Object before = field.get(service);
        runBounded("boundedTool", () -> "ok", 10_000);
        runBounded("boundedTool", () -> "ok", 10_000);
        assertSame(before, field.get(service),
                "the executor is per service, not per call — a pool created per call is a leak by construction");
    }
}
