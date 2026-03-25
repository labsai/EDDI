package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import io.nats.client.*;
import io.nats.client.api.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link NatsConversationCoordinator} using
 * Testcontainers.
 *
 * <p>
 * Spins up a real NATS 2.10 server with JetStream enabled to verify:
 * <ul>
 * <li>Sequential task execution per conversation</li>
 * <li>Concurrent execution across conversations</li>
 * <li>Dead-letter routing after max retries</li>
 * <li>Dead-letter message payload verification</li>
 * </ul>
 *
 * <p>
 * Runs via maven-failsafe-plugin ({@code mvn verify -DskipITs=false}). Requires
 * Docker running.
 * </p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@Testcontainers
class NatsConversationCoordinatorIT {

    private static final int NATS_PORT = 4222;

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine").withExposedPorts(NATS_PORT)
            .withCommand("--jetstream", "--store_dir=/data").waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private NatsConversationCoordinator coordinator;
    private Connection directConnection;

    @BeforeEach
    void setUp() throws Exception {
        String natsUrl = String.format("nats://%s:%d", natsContainer.getHost(), natsContainer.getMappedPort(NATS_PORT));

        // Create a real IRuntime that executes callables in a thread pool
        IRuntime runtime = new TestRuntime();

        // Create coordinator with real NATS connection (no mocks)
        coordinator = new NatsConversationCoordinator(runtime, null, // no metrics instance for IT
                natsUrl, "EDDI_IT_CONVERSATIONS", "EDDI_IT_DEAD_LETTERS", 3, // maxRetries
                60);

        // Start coordinator (connects to NATS, creates streams)
        coordinator.start();

        // Also create a direct connection for verification
        directConnection = Nats.connect(natsUrl);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (coordinator != null) {
            coordinator.shutdown();
        }
        if (directConnection != null) {
            directConnection.close();
        }
    }

    @Test
    @DisplayName("should execute tasks sequentially for the same conversation")
    void sequentialExecution() throws Exception {
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch allDone = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            final int index = i;
            coordinator.submitInOrder("conv-sequential", () -> {
                // Small delay to prove ordering is enforced
                Thread.sleep(50);
                executionOrder.add(index);
                allDone.countDown();
                return null;
            });
        }

        assertTrue(allDone.await(10, TimeUnit.SECONDS), "Tasks should complete within 10s");
        assertEquals(List.of(0, 1, 2), executionOrder, "Tasks should execute in submission order");
    }

    @Test
    @DisplayName("should execute tasks concurrently for different conversations")
    void concurrentConversations() throws Exception {
        CountDownLatch agenthStarted = new CountDownLatch(2);
        CountDownLatch agenthDone = new CountDownLatch(2);
        List<String> startOrder = Collections.synchronizedList(new ArrayList<>());

        coordinator.submitInOrder("conv-A", () -> {
            startOrder.add("A");
            agenthStarted.countDown();
            agenthStarted.await(5, TimeUnit.SECONDS); // wait for B to also start
            agenthDone.countDown();
            return null;
        });

        coordinator.submitInOrder("conv-B", () -> {
            startOrder.add("B");
            agenthStarted.countDown();
            agenthStarted.await(5, TimeUnit.SECONDS); // wait for A to also start
            agenthDone.countDown();
            return null;
        });

        assertTrue(agenthDone.await(10, TimeUnit.SECONDS), "both conversations should complete");
        assertEquals(2, startOrder.size(), "both tasks should have started");
    }

    @Test
    @DisplayName("should route to dead-letter after max retries exhausted")
    void deadLetterAfterMaxRetries() throws Exception {
        String convId = "conv-dead-letter-test";

        // Subscribe to dead-letter subject to verify routing
        directConnection.jetStream();
        JetStreamManagement jsm = directConnection.jetStreamManagement();

        // Create a consumer on the dead-letter stream
        try {
            jsm.getStreamInfo("EDDI_IT_DEAD_LETTERS");
        } catch (JetStreamApiException e) {
            // Stream may not exist yet if coordinator hasn't started
            // The coordinator's start() creates it
        }

        // Submit an always-failing task
        coordinator.submitInOrder(convId, () -> {
            throw new RuntimeException("intentional failure for dead-letter test");
        });

        // Wait for all retries to be exhausted + dead-letter published
        // maxRetries=3 so we wait for attempts to complete
        Thread.sleep(3000);

        // Verify message exists in dead-letter stream
        try {
            StreamInfo info = jsm.getStreamInfo("EDDI_IT_DEAD_LETTERS");
            assertTrue(info.getStreamState().getMsgCount() > 0, "Dead-letter stream should contain at least one message");
        } catch (JetStreamApiException e) {
            fail("Dead-letter stream should exist after coordinator.start()");
        }
    }

    @Test
    @DisplayName("should include conversation ID in dead-letter payload")
    void deadLetterPayloadContainsConversationId() throws Exception {
        String convId = "conv-payload-check";

        // Submit always-failing task
        coordinator.submitInOrder(convId, () -> {
            throw new RuntimeException("payload test failure");
        });

        // Wait for retries to exhaust
        Thread.sleep(3000);

        // Consume the dead-letter message
        JetStream js = directConnection.jetStream();
        JetStreamSubscription sub = js.subscribe("eddi.deadletter." + coordinator.sanitizeSubject(convId));

        Message msg = sub.nextMessage(Duration.ofSeconds(5));
        assertNotNull(msg, "Dead-letter message should be published");

        String payload = new String(msg.getData());
        assertTrue(payload.contains(convId), "Dead-letter payload should contain conversation ID, got: " + payload);
        assertTrue(payload.contains("payload test failure"), "Dead-letter payload should contain error message, got: " + payload);
    }

    @Test
    @DisplayName("should report connected status with real NATS")
    void connectionStatus() {
        assertTrue(coordinator.isConnected(), "Should be connected to NATS");
        assertEquals("CONNECTED", coordinator.getConnectionStatus());
    }

    // ==================== Test Runtime ====================

    /**
     * Simple IRuntime implementation for integration tests that uses a real thread
     * pool.
     */
    private static class TestRuntime implements IRuntime {
        private final ExecutorService executor = Executors.newFixedThreadPool(4);
        private final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        @Override
        public void init() {
        }

        @Override
        public String getVersion() {
            return "test";
        }

        @Override
        public ExecutorService getExecutorService() {
            return executor;
        }

        @Override
        public ScheduledExecutorService getScheduledExecutorService() {
            return scheduledExecutor;
        }

        @Override
        public void logVersion() {
        }

        @Override
        public <T> Future<T> submitCallable(Callable<T> callable, java.util.Map<Object, Object> threadBindings) {
            return executor.submit(callable);
        }

        @Override
        public <T> Future<T> submitCallable(Callable<T> callable, IFinishedExecution<T> callback, java.util.Map<Object, Object> threadBindings) {
            return executor.submit(() -> {
                try {
                    T result = callable.call();
                    if (callback != null) {
                        callback.onComplete(result);
                    }
                    return result;
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onFailure(e);
                    }
                    return null;
                }
            });
        }
    }
}
