/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.HandoffRunState.FiledHandoff;
import ai.labs.eddi.configs.groups.model.HandoffRunState.PendingEvent;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandoffRunStateTest {

    @Test
    void tryReserveIsAtomicUnderContention() throws Exception {
        var state = new HandoffRunState();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var granted = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        if (state.tryReserve(10)) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(10, granted.get(), "800 attempts against a cap of 10 must grant exactly 10");
        assertEquals(10, state.getUsed());
    }

    @Test
    void releaseNeverGoesBelowZero() {
        var state = new HandoffRunState();
        state.release();
        assertEquals(0, state.getUsed());
        assertTrue(state.tryReserve(1));
        assertFalse(state.tryReserve(1));
        state.release();
        assertTrue(state.tryReserve(1));
    }

    @Test
    void theFiledRecordKeepsTheNewest() {
        var state = new HandoffRunState();
        for (int i = 0; i < HandoffRunState.MAX_FILED + 5; i++) {
            state.recordFiled(new FiledHandoff("h" + i, "a", "g", "FILED", "s"));
        }
        assertEquals(HandoffRunState.MAX_FILED, state.getFiled().size());
        assertEquals("h5", state.getFiled().get(0).handoffId());
    }

    @Test
    void eventsDrainOnceInOrder() {
        var state = new HandoffRunState();
        state.queueEvent(new PendingEvent("h1", "a", "g", "FILED", null, "TOOL", "s1"));
        state.queueEvent(new PendingEvent("h2", "a", "g", "NOT_ACCEPTED", null, "TOOL", "s2"));
        state.queueEvent(null);
        assertEquals(List.of("h1", "h2"), state.drainEvents().stream().map(PendingEvent::handoffId).toList());
        assertTrue(state.drainEvents().isEmpty());
    }

    @Test
    void theStateSurvivesThePersistedDocumentButNotTheEventQueue() throws Exception {
        var json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        var gc = new GroupConversation();
        gc.setId("gc-1");
        var state = gc.handoffStateOrCreate();
        state.setHop(2);
        state.setLineage(List.of("agent:a", "group:b"));
        assertTrue(state.tryReserve(5));
        state.recordFiled(new FiledHandoff("h1", "desk", "g-desk", "QUEUED_FOR_REVIEW", "Research"));
        state.queueEvent(new PendingEvent("h1", "desk", "g-desk", "QUEUED_FOR_REVIEW", null, "TOOL", "Research"));

        GroupConversation back = json.deserialize(json.serialize(gc), GroupConversation.class);
        var restored = back.getHandoffState();
        assertNotNull(restored);
        assertEquals(2, restored.getHop());
        assertEquals(List.of("agent:a", "group:b"), restored.getLineage());
        assertEquals(1, restored.getUsed(), "the cap survives a pause and resume");
        assertEquals("h1", restored.getFiled().get(0).handoffId());
        assertTrue(restored.drainEvents().isEmpty(), "the SSE queue is transient");
    }

    @Test
    void anOldDocumentWithoutTheFieldReadsAsHopZero() throws Exception {
        var json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        GroupConversation old = json.deserialize("{\"id\":\"gc-old\"}", GroupConversation.class);
        assertNull(old.getHandoffState());
        assertEquals(0, old.handoffStateOrCreate().getHop());
    }

    @Test
    void handoffStateOrCreateReturnsOneSharedInstance() {
        var gc = new GroupConversation();
        assertSame(gc.handoffStateOrCreate(), gc.handoffStateOrCreate());
    }
}
