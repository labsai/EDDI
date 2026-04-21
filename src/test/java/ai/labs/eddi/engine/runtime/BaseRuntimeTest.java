package ai.labs.eddi.engine.runtime;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for BaseRuntime — construction, version, and callable submission.
 */
class BaseRuntimeTest {

    private BaseRuntime runtime;
    private ManagedExecutor mockExecutor;
    private ExecutorService realExecutor;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new BaseRuntime("TestProject", "1.2.3");

        // Create a mock ManagedExecutor that delegates to a real single-thread executor
        mockExecutor = Mockito.mock(ManagedExecutor.class);
        realExecutor = Executors.newSingleThreadExecutor();
        when(mockExecutor.submit(any(Callable.class))).thenAnswer(inv -> {
            Callable<?> callable = inv.getArgument(0);
            return realExecutor.submit(callable);
        });

        var executorField = BaseRuntime.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        executorField.set(runtime, mockExecutor);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.getScheduledExecutorService().shutdownNow();
        }
        if (realExecutor != null) {
            realExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("getVersion returns configured version")
    void getVersion() {
        assertEquals("1.2.3", runtime.getVersion());
    }

    @Test
    @DisplayName("logVersion does not throw")
    void logVersion() {
        assertDoesNotThrow(() -> runtime.logVersion());
    }

    @Test
    @DisplayName("init called twice logs warning but does not crash")
    void doubleInit() {
        assertDoesNotThrow(() -> runtime.init());
    }

    @Test
    @DisplayName("getExecutorService returns injected executor")
    void getExecutorService() {
        assertSame(mockExecutor, runtime.getExecutorService());
    }

    @Test
    @DisplayName("getScheduledExecutorService returns non-null")
    void getScheduledExecutorService() {
        assertNotNull(runtime.getScheduledExecutorService());
    }

    @Test
    @DisplayName("submitCallable executes and returns result")
    void submitCallable() throws Exception {
        Future<String> future = runtime.submitCallable(() -> "hello", null);
        assertEquals("hello", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("submitCallable with callback invokes onComplete")
    void submitCallable_withCallback() throws Exception {
        CompletableFuture<String> completed = new CompletableFuture<>();

        runtime.submitCallable(
                () -> "result",
                new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(String result) {
                        completed.complete(result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        completed.completeExceptionally(t);
                    }
                },
                null);

        assertEquals("result", completed.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("submitCallable with failing callable invokes onFailure")
    void submitCallable_failure() throws Exception {
        CompletableFuture<Throwable> failureCaught = new CompletableFuture<>();

        runtime.submitCallable(
                () -> {
                    throw new RuntimeException("boom");
                },
                new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(Object result) {
                        failureCaught.completeExceptionally(new AssertionError("should not complete"));
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        failureCaught.complete(t);
                    }
                },
                null);

        Throwable error = failureCaught.get(5, TimeUnit.SECONDS);
        assertEquals("boom", error.getMessage());
    }

    @Test
    @DisplayName("construction with empty project name logs error but works")
    void emptyProjectName() {
        assertDoesNotThrow(() -> {
            var rt = new BaseRuntime("", "0.0.1");
            rt.getScheduledExecutorService().shutdownNow();
        });
    }
}
