/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.JetStream;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.PublishAck;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StreamConfiguration;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M-E4 — the NATS coordinator's ways of losing a turn: a dropped turn that
 * never answered its caller, a dead-letter publish that wedged the queue, and a
 * conversation stream nothing bounded.
 */
@DisplayName("NatsConversationCoordinator — turn loss (M-E4)")
class NatsConversationCoordinatorTurnLossTest {

    private static final String CONVERSATION_ID = "conv-nats-1";

    private IRuntime runtime;
    private JetStream jetStream;
    private NatsConversationCoordinator coordinator;
    /** Completion listeners of the tasks handed to the runtime, in order. */
    private final List<IRuntime.IFinishedExecution<Void>> listeners = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        jetStream = mock(JetStream.class);
        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        doReturn(false).when(metricsInstance).isResolvable();

        coordinator = new NatsConversationCoordinator(runtime, metricsInstance, new SimpleMeterRegistry(),
                "nats://localhost:4222", "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 10000);
        var jsField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jsField.setAccessible(true);
        jsField.set(coordinator, jetStream);

        PublishAck ack = mock(PublishAck.class);
        doReturn(ack).when(jetStream).publish(anyString(), any(byte[].class));
    }

    @SuppressWarnings("unchecked")
    private void runtimeAcceptsThenRejects(int accepted) {
        var calls = new int[1];
        doAnswer(inv -> {
            if (calls[0]++ >= accepted) {
                throw new RejectedExecutionException("runtime saturated");
            }
            listeners.add(inv.getArgument(1));
            return CompletableFuture.completedFuture(null);
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    @Test
    @DisplayName("a queued turn dropped because it could not be scheduled is told so, and can answer its caller")
    void droppedTurnIsNotifiedViaOnDiscarded() {
        runtimeAcceptsThenRejects(1);
        AtomicReference<Throwable> discardedWith = new AtomicReference<>();
        IDiscardableTask queued = new IDiscardableTask() {
            @Override
            public Void call() {
                fail("a discarded task must never run");
                return null;
            }

            @Override
            public void onDiscarded(Throwable cause) {
                discardedWith.set(cause);
            }
        };

        coordinator.submitInOrder(CONVERSATION_ID, () -> null);
        coordinator.submitInOrder(CONVERSATION_ID, queued);
        listeners.get(0).onComplete(null);

        assertInstanceOf(RejectedExecutionException.class, discardedWith.get(),
                "without the hook the REST caller waited for its timeout and an SSE stream hung");
        assertTrue(coordinator.getQueueDepths().isEmpty(), "the queue must drain");
    }

    @Test
    @DisplayName("a dead-letter publish that throws an unchecked exception does not wedge the conversation queue")
    void uncheckedDeadLetterFailureKeepsTheQueueDraining() throws Exception {
        // First task runs, second cannot be scheduled, third must still get its turn.
        var calls = new int[1];
        doAnswer(inv -> {
            int call = calls[0]++;
            if (call == 1) {
                throw new RejectedExecutionException("runtime saturated");
            }
            listeners.add(inv.getArgument(1));
            return CompletableFuture.completedFuture(null);
        }).when(runtime).submitCallable(any(), any(), any());
        doThrow(new IllegalStateException("connection closed")).when(jetStream).publish(startsWith("eddi.deadletter."), any(byte[].class));

        coordinator.submitInOrder(CONVERSATION_ID, () -> null);
        coordinator.submitInOrder(CONVERSATION_ID, () -> null);
        coordinator.submitInOrder(CONVERSATION_ID, () -> null);
        listeners.get(0).onComplete(null);

        assertEquals(2, listeners.size(), "the third turn must be scheduled after the second was dead-lettered");
    }

    @Test
    @DisplayName("an unchecked failure of the ordering publish degrades to local execution instead of failing the turn")
    void uncheckedPublishFailureStillExecutesLocally() throws Exception {
        runtimeAcceptsThenRejects(Integer.MAX_VALUE);
        doThrow(new IllegalStateException("connection draining")).when(jetStream).publish(startsWith("eddi.conversation."), any(byte[].class));

        assertDoesNotThrow(() -> coordinator.submitInOrder(CONVERSATION_ID, () -> null));

        assertEquals(1, listeners.size(), "the turn must still run: the marker is an ordering hint nobody consumes");
    }

    @Test
    @DisplayName("the conversation stream is bounded by age, count and bytes, discarding the oldest marker")
    void conversationStreamIsBounded() {
        StreamConfiguration config = coordinator.conversationStreamConfiguration();

        assertEquals(RetentionPolicy.WorkQueue, config.getRetentionPolicy());
        assertEquals(DiscardPolicy.Old, config.getDiscardPolicy());
        assertEquals(Duration.ofHours(1), config.getMaxAge());
        assertEquals(100_000L, config.getMaxMsgs());
        assertEquals(256L * 1024 * 1024, config.getMaxBytes());
    }
}
