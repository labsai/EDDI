package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AgentExecutionHelper (retry logic).
 */
class AgentExecutionHelperTest {

    // ==================== Retry Logic Tests ====================

    @Test
    @DisplayName("executeWithRetry should succeed on first attempt")
    void testExecuteWithRetry_SuccessOnFirstAttempt() throws LifecycleException {
        var task = new LlmConfiguration.Task();
        AtomicInteger attempts = new AtomicInteger(0);

        String result = AgentExecutionHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return "success";
                },
                task,
                "Test action");

        assertEquals("success", result);
        assertEquals(1, attempts.get(), "Should only attempt once on success");
    }

    @Test
    @DisplayName("executeWithRetry should retry on retryable error")
    void testExecuteWithRetry_RetryOnTimeout() throws LifecycleException {
        var task = new LlmConfiguration.Task();
        var retryConfig = new LlmConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(3);
        retryConfig.setBackoffDelayMs(10L); // Short delay for testing
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        String result = AgentExecutionHelper.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw new RuntimeException("Connection timeout");
                    }
                    return "success";
                },
                task,
                "Test action");

        assertEquals("success", result);
        assertEquals(3, attempts.get(), "Should retry until success");
    }

    @Test
    @DisplayName("executeWithRetry should fail after max attempts")
    void testExecuteWithRetry_FailAfterMaxAttempts() {
        var task = new LlmConfiguration.Task();
        var retryConfig = new LlmConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(2);
        retryConfig.setBackoffDelayMs(10L);
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        LifecycleException exception = assertThrows(LifecycleException.class,
                () -> AgentExecutionHelper.executeWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            throw new RuntimeException("Connection timeout");
                        },
                        task,
                        "Test action"));

        assertEquals(2, attempts.get(), "Should attempt max times");
        assertTrue(exception.getMessage().contains("failed after 2 attempts"));
    }

    @Test
    @DisplayName("executeWithRetry should fail immediately on non-retryable error")
    void testExecuteWithRetry_FailImmediatelyOnNonRetryableError() {
        var task = new LlmConfiguration.Task();
        var retryConfig = new LlmConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(3);
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        LifecycleException exception = assertThrows(LifecycleException.class,
                () -> AgentExecutionHelper.executeWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            throw new RuntimeException("Invalid API key");
                        },
                        task,
                        "Test action"));

        assertEquals(1, attempts.get(), "Should fail on first attempt for non-retryable error");
        assertTrue(exception.getMessage().contains("Invalid API key"));
    }

    @Test
    @DisplayName("executeWithRetry should use default config when retry is null")
    void testExecuteWithRetry_DefaultConfig() throws LifecycleException {
        var task = new LlmConfiguration.Task();
        // retry is null - should use defaults

        String result = AgentExecutionHelper.executeWithRetry(
                () -> "success",
                task,
                "Test action");

        assertEquals("success", result);
    }

    @Test
    @DisplayName("executeWithRetry should handle rate limit error as retryable")
    void testExecuteWithRetry_RateLimitIsRetryable() throws LifecycleException {
        var task = new LlmConfiguration.Task();
        var retryConfig = new LlmConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(3);
        retryConfig.setBackoffDelayMs(10L);
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        String result = AgentExecutionHelper.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw new RuntimeException("Rate limit exceeded");
                    }
                    return "success";
                },
                task,
                "Test action");

        assertEquals("success", result);
        assertEquals(2, attempts.get());
    }

    // ==================== RetryConfiguration Default Tests ====================

    @Test
    @DisplayName("RetryConfiguration should have correct defaults")
    void testRetryConfigurationDefaults() {
        var retryConfig = new LlmConfiguration.RetryConfiguration();

        assertEquals(3, retryConfig.getMaxAttempts());
        assertEquals(1000L, retryConfig.getBackoffDelayMs());
        assertEquals(2.0, retryConfig.getBackoffMultiplier());
        assertEquals(10000L, retryConfig.getMaxBackoffDelayMs());
    }
}
