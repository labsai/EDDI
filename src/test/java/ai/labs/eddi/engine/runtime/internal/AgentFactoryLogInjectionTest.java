/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.labs.eddi.utils.LogCaptureSupport.FORGED_RECORD;
import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Log injection (CWE-117) regression tests for {@link AgentFactory}.
 *
 * <p>
 * Every agent id this class logs arrives as a path parameter —
 * {@code /administration/{environment}/deploy/{agentId}} and the conversation
 * endpoints — so a CR/LF in one closes the real record and lets the remainder
 * read as a second line the server wrote itself.
 * {@code waitForDeploymentCompletion} quotes the id on five lines and
 * {@code logAgentDeployment} on two more; the file already sanitized its
 * {@code debugf} calls and these were simply missed.
 * </p>
 *
 * <p>
 * These assert the CONTRACT, not the call: drive a forged record through the
 * real path and require that nothing carrying a record boundary reached the
 * log. Removing any {@code sanitize(...)} on the pinned line fails them.
 * </p>
 */
@DisplayName("AgentFactory — log injection (CWE-117)")
class AgentFactoryLogInjectionTest {

    private static final Deployment.Environment ENV = Deployment.Environment.test;

    /** The agent id as an attacker supplies it on the path. */
    private static final String POISONED_AGENT_ID = "aabbccddeeff112233445566" + FORGED_RECORD;

    private IAgentStoreClientLibrary agentStoreClientLibrary;
    private IDeploymentListener deploymentListener;
    private AgentFactory factory;

    @BeforeEach
    void setUp() {
        agentStoreClientLibrary = mock(IAgentStoreClientLibrary.class);
        deploymentListener = mock(IDeploymentListener.class);
        factory = new AgentFactory(agentStoreClientLibrary, deploymentListener, new SimpleMeterRegistry());
    }

    /**
     * Parks a deployment of the poisoned id inside its store lookup, which leaves
     * the {@code IN_PROGRESS} placeholder published — the only state in which
     * {@code getAgent} waits at all, and therefore the only way to reach the five
     * log calls in {@code waitForDeploymentCompletion}.
     *
     * <p>
     * The deployment's own {@code logAgentDeployment} INFO fires <em>before</em>
     * the store lookup, so by the time this method returns that line is already out
     * and safely outside the capture window. Without that ordering every test here
     * would be satisfied by whichever line leaked first rather than by the one it
     * names, and reverting a single {@code sanitize(...)} would not fail the right
     * test.
     * </p>
     *
     * @param releaseLookup
     *            counted down by the caller, once it has asserted, to let the
     *            deployment finish
     * @return the thread running the deployment, to be joined after the release
     */
    private Thread deploymentParkedInStoreLookup(CountDownLatch releaseLookup) throws Exception {
        var lookupStarted = new CountDownLatch(1);
        var agent = new Agent(POISONED_AGENT_ID, 1);

        when(agentStoreClientLibrary.getAgent(POISONED_AGENT_ID, 1)).thenAnswer(invocation -> {
            lookupStarted.countDown();
            assertTrue(releaseLookup.await(10, TimeUnit.SECONDS), "the test never released the store lookup");
            return agent;
        });

        var deploying = Thread.ofVirtual().start(() -> {
            try {
                factory.deployAgent(ENV, POISONED_AGENT_ID, 1, null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertTrue(lookupStarted.await(10, TimeUnit.SECONDS), "the deployment never reached its store lookup");
        return deploying;
    }

    /**
     * A registered deployment future whose timed wait fails the way the caller
     * under test sees it.
     *
     * <p>
     * Hand-written rather than mocked on purpose: the default mock maker does not
     * intercept {@link CompletableFuture#get(long, TimeUnit)}, so a stubbed mock
     * silently ran the real 60-second wait instead of throwing.
     * </p>
     */
    private static CompletableFuture<Void> futureWhoseWaitThrows(Exception failure) {
        return new CompletableFuture<>() {
            @Override
            public Void get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
                if (failure instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
                throw (TimeoutException) failure;
            }
        };
    }

    @Test
    @DisplayName("a CR/LF agent id cannot forge a record through the still-deploying DEBUG")
    void stillDeployingWithNoRegisteredFuture() throws Exception {
        var releaseLookup = new CountDownLatch(1);
        var deploying = deploymentParkedInStoreLookup(releaseLookup);
        try {
            // Nothing registered a deployment future for this id, so there was never
            // anything to await and "not ready yet" is an ordinary DEBUG answer.
            when(deploymentListener.getRegisteredDeploymentEvent(POISONED_AGENT_ID, 1)).thenReturn(null);

            List<String> captured = captureLogsOf(AgentFactory.class,
                    () -> assertNull(assertDoesNotThrow(() -> factory.getAgent(ENV, POISONED_AGENT_ID, 1))));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("is still deploying")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "AgentFactory.waitForDeploymentCompletion's still-deploying DEBUG");
        } finally {
            releaseLookup.countDown();
            deploying.join();
        }
    }

    @Test
    @DisplayName("a CR/LF agent id cannot forge a record through the still-IN_PROGRESS-after-wait ERROR")
    void stillInProgressAfterTheWait() throws Exception {
        var releaseLookup = new CountDownLatch(1);
        var deploying = deploymentParkedInStoreLookup(releaseLookup);
        try {
            // A future WAS registered and has completed, yet the agent is still
            // IN_PROGRESS — the deployment did not finish and that is an ERROR.
            when(deploymentListener.getRegisteredDeploymentEvent(POISONED_AGENT_ID, 1))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<String> captured = captureLogsOf(AgentFactory.class,
                    () -> assertNull(assertDoesNotThrow(() -> factory.getAgent(ENV, POISONED_AGENT_ID, 1))));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("did not complete successfully")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "AgentFactory.waitForDeploymentCompletion's still-IN_PROGRESS ERROR");
        } finally {
            releaseLookup.countDown();
            deploying.join();
        }
    }

    @Test
    @DisplayName("a CR/LF agent id cannot forge a record through the agent-gone-after-wait ERROR")
    void agentGoneAfterTheWait() throws Exception {
        var releaseLookup = new CountDownLatch(1);
        var deploying = deploymentParkedInStoreLookup(releaseLookup);
        try {
            when(deploymentListener.getRegisteredDeploymentEvent(POISONED_AGENT_ID, 1)).thenAnswer(invocation -> {
                // The registry lookup runs before the wait, so undeploying from here is
                // the deterministic way to make the post-wait re-fetch come back empty.
                factory.undeployAgent(ENV, POISONED_AGENT_ID, 1);
                return CompletableFuture.completedFuture(null);
            });

            List<String> captured = captureLogsOf(AgentFactory.class,
                    () -> assertNull(assertDoesNotThrow(() -> factory.getAgent(ENV, POISONED_AGENT_ID, 1))));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("did not complete successfully")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "AgentFactory.waitForDeploymentCompletion's agent-gone ERROR");
        } finally {
            releaseLookup.countDown();
            deploying.join();
        }
    }

    @Test
    @DisplayName("a CR/LF agent id cannot forge a record through the wait-timed-out WARN")
    void waitTimedOut() throws Exception {
        var releaseLookup = new CountDownLatch(1);
        var deploying = deploymentParkedInStoreLookup(releaseLookup);
        try {
            var timingOut = futureWhoseWaitThrows(new TimeoutException("still deploying"));
            when(deploymentListener.getRegisteredDeploymentEvent(POISONED_AGENT_ID, 1)).thenReturn(timingOut);

            List<String> captured = captureLogsOf(AgentFactory.class,
                    () -> assertNull(assertDoesNotThrow(() -> factory.getAgent(ENV, POISONED_AGENT_ID, 1))));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("was still deploying after")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "AgentFactory.waitForDeploymentCompletion's timeout WARN");
        } finally {
            releaseLookup.countDown();
            deploying.join();
        }
    }

    @Test
    @DisplayName("a CR/LF agent id cannot forge a record through the interrupted-while-waiting WARN")
    void interruptedWhileWaiting() throws Exception {
        var releaseLookup = new CountDownLatch(1);
        var deploying = deploymentParkedInStoreLookup(releaseLookup);
        try {
            var interrupting = futureWhoseWaitThrows(new InterruptedException("the caller gave up"));
            when(deploymentListener.getRegisteredDeploymentEvent(POISONED_AGENT_ID, 1)).thenReturn(interrupting);

            List<String> captured = captureLogsOf(AgentFactory.class,
                    () -> assertNull(assertDoesNotThrow(() -> factory.getAgent(ENV, POISONED_AGENT_ID, 1))));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Interrupted while waiting for agent")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "AgentFactory.waitForDeploymentCompletion's interrupted WARN");
        } finally {
            // The production path restores the interrupt flag rather than swallowing
            // it, so clear it here or the next test on this thread inherits it.
            Thread.interrupted();
            releaseLookup.countDown();
            deploying.join();
        }
    }

    @Test
    @DisplayName("a CR/LF agent id cannot forge a record through either deployment-progress INFO")
    void deploymentProgressInfoLines() throws Exception {
        when(agentStoreClientLibrary.getAgent(POISONED_AGENT_ID, 1)).thenReturn(new Agent(POISONED_AGENT_ID, 1));

        List<String> captured = captureLogsOf(AgentFactory.class,
                () -> assertDoesNotThrow(() -> factory.deployAgent(ENV, POISONED_AGENT_ID, 1, null)));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Deploying agent...")),
                "the IN_PROGRESS line under test did not fire; captured: " + captured);
        assertTrue(captured.stream().anyMatch(value -> value.contains("Agent deployed with status")),
                "the terminal-status line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "AgentFactory.logAgentDeployment's deployment INFO lines");
    }
}
