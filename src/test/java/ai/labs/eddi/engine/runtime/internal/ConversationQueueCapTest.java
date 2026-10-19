/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@code eddi.conversation.max-queued-turns-per-conversation}: turns of one
 * conversation run one after another, so past a few waiting turns more queueing
 * only holds permits and loaded conversations. The turn past the cap is refused
 * with the 503 every surface maps a {@link RejectedExecutionException} to.
 */
class ConversationQueueCapTest {

    private SimpleMeterRegistry meterRegistry;
    private InMemoryConversationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        coordinator = new InMemoryConversationCoordinator(mock(IRuntime.class), meterRegistry, 10000, 1000);
        coordinator.maxQueuedTurnsPerConversation = 2;
    }

    @SuppressWarnings("unchecked")
    private static Callable<Void> turn() {
        return mock(Callable.class);
    }

    private double rejected() {
        var counter = meterRegistry.find("eddi.turn.rejected").tag("reason", "conversation_queue").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("one running plus the cap's worth waiting are accepted; the next is refused and counted")
    void refusesPastTheCap() {
        coordinator.submitInOrder("conv-a", turn()); // running
        coordinator.submitInOrder("conv-a", turn()); // waiting 1
        coordinator.submitInOrder("conv-a", turn()); // waiting 2

        RejectedExecutionException refused = assertThrows(RejectedExecutionException.class,
                () -> coordinator.submitInOrder("conv-a", turn()));
        assertTrue(refused.getMessage().contains("max-queued-turns-per-conversation"), refused.getMessage());
        assertEquals(1.0, rejected());
        assertEquals(3, coordinator.getQueueDepths().get("conv-a"), "the refused turn is not queued");
    }

    @Test
    @DisplayName("the cap is per conversation")
    void perConversation() {
        for (int i = 0; i < 3; i++) {
            coordinator.submitInOrder("conv-a", turn());
        }
        assertDoesNotThrow(() -> coordinator.submitInOrder("conv-b", turn()));
    }

    @Test
    @DisplayName("0 disables the cap")
    void zeroDisables() {
        coordinator.maxQueuedTurnsPerConversation = 0;
        for (int i = 0; i < 40; i++) {
            coordinator.submitInOrder("conv-a", turn());
        }
        assertEquals(0.0, rejected());
    }

    @Test
    @DisplayName("the default allows twenty waiting turns")
    void defaultIsTwenty() {
        var fresh = new InMemoryConversationCoordinator(mock(IRuntime.class), new SimpleMeterRegistry(), 10000, 1000);
        for (int i = 0; i < 21; i++) {
            fresh.submitInOrder("conv-a", turn());
        }
        assertThrows(RejectedExecutionException.class, () -> fresh.submitInOrder("conv-a", turn()));
    }
}
