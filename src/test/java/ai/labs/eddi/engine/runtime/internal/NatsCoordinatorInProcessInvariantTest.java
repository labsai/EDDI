/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the node-affinity invariant that {@code LiveDiscussionRegistry} — and
 * with it every group task / artifact / recruit tool — silently depends on.
 * <p>
 * The registry is per-node and holds the live {@code GroupConversation} the
 * discussion loop is mutating. Its Javadoc states the dependency explicitly:
 * {@code MemberTurnExecutor#executeAgentTurn} always runs a member's turn
 * in-process, so a tool invoked during that turn can hold a live reference into
 * the registry. If a member turn could ever be routed to another node, those
 * tools would not fail — they would silently not be assembled, and the model
 * would lose them mid-discussion with nothing in the logs.
 * <p>
 * That invariant currently holds because the NATS coordinator uses JetStream
 * purely as a distributed <em>ordering</em> primitive: it publishes the
 * conversation id as a marker and then executes the callable through the local
 * runtime. Nothing enforces that, and the payload is the only thing standing
 * between "ordering primitive" and "work queue" — so this test enforces it,
 * rather than leaving a changelog note to be trusted.
 */
@DisplayName("NATS coordinator — callables execute in-process")
class NatsCoordinatorInProcessInvariantTest {

    @SuppressWarnings("unchecked")
    private static NatsConversationCoordinator coordinator(IRuntime runtime) {
        Instance<NatsMetrics> metrics = mock(Instance.class);
        when(metrics.isResolvable()).thenReturn(false);
        return new NatsConversationCoordinator(runtime, metrics, new SimpleMeterRegistry(), "nats://localhost:4222", "eddi-conversations",
                "eddi-deadletter", 3, 100);
    }

    /**
     * Two invariants in one, because the same line carries both.
     * <ol>
     * <li>The callable is handed to {@code IRuntime}, i.e. executed on THIS JVM —
     * the node-affinity invariant the group tools depend on.</li>
     * <li>It is handed over even when the publish fails. The publish is an ordering
     * marker, never the work, so no publish failure may cost a conversation its
     * turn. {@code publishAndExecute} used to catch only {@code IOException} and
     * {@code JetStreamApiException}, so an unchecked failure — the NATS client
     * throws {@code IllegalStateException} on a closed or draining connection —
     * escaped the method and skipped the execution entirely, silently dropping the
     * turn while the catch block promised "executing locally".</li>
     * </ol>
     */
    @Test
    @DisplayName("the submitted callable is handed to the local runtime, never to a remote consumer")
    void callableRunsThroughLocalRuntime() {
        var runtime = mock(IRuntime.class);
        var executed = new AtomicReference<Callable<Void>>();

        doAnswer(invocation -> {
            Callable<Void> callable = invocation.getArgument(0);
            executed.set(callable);
            callable.call();
            return CompletableFuture.completedFuture(null);
        }).when(runtime).submitCallable(any(), any(IRuntime.IFinishedExecution.class), any());

        var ranInProcess = new AtomicReference<String>();
        Callable<Void> work = () -> {
            ranInProcess.set(Thread.currentThread().getName());
            return null;
        };

        coordinator(runtime).submitInOrder("conversation-1", work);

        assertSame(work, executed.get(), "the coordinator must hand the original callable to the local runtime — "
                + "if it were ever serialized onto the stream instead, a member turn could run on another node "
                + "and LiveDiscussionRegistry would silently withhold every group tool");
        assertEquals(Thread.currentThread().getName(), ranInProcess.get(), "the work must have run in this process");
    }
}
