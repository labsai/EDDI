/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.IDeadLetterStore;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseInfo;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.engine.cluster.lease.LocalLeaseManager;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IDescribedTask;
import ai.labs.eddi.engine.runtime.ILeaseAwareTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ClusterConversationCoordinator")
class ClusterConversationCoordinatorTest {

    /** A lease manager whose grants the test controls. */
    static final class ScriptedLeases implements IConversationLeaseManager {
        final List<CompletableFuture<LeaseHandle>> pending = new CopyOnWriteArrayList<>();
        final List<LeaseHandle> released = new CopyOnWriteArrayList<>();
        volatile boolean grantImmediately = true;
        final AtomicInteger fence = new AtomicInteger();

        LeaseHandle handle(String key) {
            long f = fence.incrementAndGet();
            return new LeaseHandle() {
                @Override
                public String key() {
                    return key;
                }

                @Override
                public String conversationId() {
                    return key.substring(2);
                }

                @Override
                public Long fence() {
                    return f;
                }

                @Override
                public boolean isLost() {
                    return false;
                }

                @Override
                public void onLost(Runnable callback) {
                }
            };
        }

        volatile boolean stopped;
        volatile boolean releasedAll;

        @Override
        public void stopAcquiring() {
            stopped = true;
            for (CompletableFuture<LeaseHandle> f : pending) {
                f.completeExceptionally(new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null, "stopping"));
            }
        }

        @Override
        public void releaseAll() {
            releasedAll = true;
        }

        @Override
        public CompletionStage<LeaseHandle> acquireKey(String key, Duration maxWait) {
            CompletableFuture<LeaseHandle> f = new CompletableFuture<>();
            if (stopped) {
                f.completeExceptionally(new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null, "stopping"));
            } else if (grantImmediately) {
                f.complete(handle(key));
            } else {
                pending.add(f);
            }
            return f;
        }

        @Override
        public Optional<LeaseHandle> tryAcquireKey(String key) {
            return Optional.empty();
        }

        @Override
        public void release(LeaseHandle handle) {
            released.add(handle);
        }

        @Override
        public Optional<LeaseInfo> peekKey(String key) {
            return Optional.empty();
        }

        @Override
        public int heldCount() {
            return 0;
        }
    }

    /**
     * Runs each callable on a pool and reports to the callbacks, like BaseRuntime.
     */
    static final class PoolRuntime implements IRuntime {
        final ExecutorService pool = Executors.newCachedThreadPool();
        volatile boolean reject;

        @Override
        public void init() {
        }

        @Override
        public String getVersion() {
            return "test";
        }

        @Override
        public ExecutorService getExecutorService() {
            return pool;
        }

        @Override
        public ScheduledExecutorService getScheduledExecutorService() {
            return null;
        }

        @Override
        public void logVersion() {
        }

        @Override
        public <T> Future<T> submitCallable(Callable<T> callable, Map<Object, Object> threadBindings) {
            return pool.submit(callable);
        }

        @Override
        public <T> Future<T> submitCallable(Callable<T> callable, IFinishedExecution<T> done, Map<Object, Object> threadBindings) {
            if (reject) {
                throw new RejectedExecutionException("pool saturated");
            }
            return pool.submit(() -> {
                try {
                    T result = callable.call();
                    done.onComplete(result);
                    return result;
                } catch (Throwable t) {
                    done.onFailure(t);
                    return null;
                }
            });
        }
    }

    /** A turn that records what it saw. */
    static class Turn implements ILeaseAwareTask, IDescribedTask {
        final String name;
        final List<String> log;
        final AtomicReference<LeaseHandle> lease = new AtomicReference<>();
        final AtomicReference<Throwable> discarded = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile RuntimeException failWith;

        Turn(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void bindLease(LeaseHandle l) {
            lease.set(l);
        }

        @Override
        public Map<String, Object> describe() {
            return Map.of("input", name, "agentId", "agent1");
        }

        @Override
        public Void call() {
            try {
                log.add(name);
                if (failWith != null) {
                    throw failWith;
                }
                return null;
            } finally {
                done.countDown();
            }
        }

        @Override
        public void onDiscarded(Throwable cause) {
            discarded.set(cause);
            done.countDown();
        }
    }

    /** A dead-letter store kept in a list. */
    static final class ListStore implements IDeadLetterStore {
        final List<DeadLetterEntry> entries = new CopyOnWriteArrayList<>();
        volatile boolean down;
        final AtomicInteger seq = new AtomicInteger();

        @Override
        public String append(String conversationId, String error, long timestamp, Map<String, Object> turn) {
            if (down) {
                throw new ClusterUnavailableException("down");
            }
            String id = String.valueOf(seq.incrementAndGet());
            entries.add(new DeadLetterEntry(id, conversationId, error, timestamp, "{}", turn));
            return id;
        }

        @Override
        public List<DeadLetterEntry> list(int limit, String after) {
            return new ArrayList<>(entries);
        }

        @Override
        public Optional<DeadLetterEntry> get(String id) {
            return entries.stream().filter(e -> e.id().equals(id)).findFirst();
        }

        @Override
        public boolean delete(String id) {
            return entries.removeIf(e -> e.id().equals(id));
        }

        @Override
        public int purge() {
            int n = entries.size();
            entries.clear();
            return n;
        }

        @Override
        public int purgeConversation(String conversationId) {
            int before = entries.size();
            entries.removeIf(e -> conversationId.equals(e.conversationId()));
            return before - entries.size();
        }

        @Override
        public long count() {
            return entries.size();
        }
    }

    private ScriptedLeases leases;
    private PoolRuntime runtime;
    private ListStore store;
    private ClusterConversationCoordinator coordinator;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        leases = new ScriptedLeases();
        runtime = new PoolRuntime();
        store = new ListStore();
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.isConnected()).thenReturn(true);
        coordinator = new ClusterConversationCoordinator(runtime, new SimpleMeterRegistry(), leases, store, connections,
                mock(ClusterPresence.class), 10_000, 1000, Duration.ofSeconds(45));
    }

    @AfterEach
    void tearDown() {
        runtime.pool.shutdownNow();
    }

    private static void await(Turn t) throws InterruptedException {
        assertTrue(t.done.await(5, TimeUnit.SECONDS), "turn " + t.name + " did not finish");
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!coordinator.getQueueDepths().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("turns of one conversation run FIFO, each under its own lease, and every lease is released")
    void fifoUnderLease() throws Exception {
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Turn t = new Turn("t" + i, log);
            turns.add(t);
            coordinator.submitInOrder("conv1", t);
        }
        for (Turn t : turns) {
            await(t);
        }
        awaitIdle();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expected.add("t" + i);
            assertNotNull(turns.get(i).lease.get(), "lease bound to the task before it ran");
        }
        assertEquals(expected, log);
        assertEquals(20, leases.released.size());
        assertEquals(20, coordinator.getTotalProcessed());
    }

    @Test
    @DisplayName("a lease that does not come in time discards the turn as busy — not dead-lettered — and the queue moves on")
    void acquireTimeoutDiscardsWithoutDeadLetter() throws Exception {
        leases.grantImmediately = false;
        Turn first = new Turn("first", log);
        Turn second = new Turn("second", log);
        coordinator.submitInOrder("conv1", first);
        coordinator.submitInOrder("conv1", second);
        assertEquals(1, leases.pending.size(), "only the head waits for the lease");
        leases.pending.get(0).completeExceptionally(
                new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, "nodeB", "held by nodeB"));
        await(first);
        assertInstanceOf(LeaseUnavailableException.class, first.discarded.get());
        assertTrue(log.isEmpty(), "the discarded turn never ran");
        assertTrue(store.entries.isEmpty(), "input never consumed — no dead letter");
        // the next queued turn now asks for the lease
        long deadline = System.currentTimeMillis() + 2000;
        while (leases.pending.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        leases.pending.get(1).complete(leases.handle("c.conv1"));
        await(second);
        assertEquals(List.of("second"), log);
    }

    @Test
    @DisplayName("shutdown: turns waiting for their lease are discarded at once (409, no dead letter); leases go back after the drain")
    void shutdownAnswersWaitersAndReleasesLeases() throws Exception {
        leases.grantImmediately = false;
        Turn first = new Turn("first", log);
        Turn second = new Turn("second", log);
        coordinator.submitInOrder("conv1", first);
        coordinator.submitInOrder("conv1", second);

        coordinator.beginShutdown();

        await(first);
        await(second);
        assertInstanceOf(LeaseUnavailableException.class, first.discarded.get());
        assertInstanceOf(LeaseUnavailableException.class, second.discarded.get(),
                "the turn queued behind it must not wait for a lease either");
        assertTrue(log.isEmpty(), "neither turn ran");
        assertTrue(store.entries.isEmpty(), "their input was never consumed — no dead letters");
        assertFalse(leases.releasedAll, "held leases stay held while the drain runs");

        coordinator.completeShutdown();
        assertTrue(leases.releasedAll);
    }

    @Test
    @DisplayName("a runtime rejection after the lease came dead-letters the turn, tells it, releases the lease")
    void rejectionAfterLeaseDeadLettersAndDiscards() throws Exception {
        runtime.reject = true;
        Turn t = new Turn("t", log);
        coordinator.submitInOrder("conv1", t);
        await(t);
        assertInstanceOf(RejectedExecutionException.class, t.discarded.get());
        assertEquals(1, store.entries.size());
        assertEquals("t", store.entries.get(0).turn().get("input"), "the turn descriptor travels with the dead letter");
        assertEquals(1, leases.released.size());
        awaitIdle();
        assertTrue(coordinator.getQueueDepths().isEmpty());
    }

    @Test
    @DisplayName("a turn that fails after it started is dead-lettered once and its lease released")
    void failureDeadLettersAndReleases() throws Exception {
        Turn t = new Turn("boom", log);
        t.failWith = new IllegalStateException("LLM said 500");
        coordinator.submitInOrder("conv1", t);
        await(t);
        awaitIdle();
        assertEquals(1, store.entries.size());
        assertEquals("LLM said 500", store.entries.get(0).error());
        assertEquals(1, leases.released.size());
        assertEquals(1, coordinator.getTotalDeadLettered());
        assertTrue(store.entries.get(0).isReplayable());
    }

    @Test
    @DisplayName("dead letters fall back to the node-local ring while the stream is unavailable")
    void deadLetterFallsBackWhenStreamDown() throws Exception {
        store.down = true;
        Turn t = new Turn("boom", log);
        t.failWith = new IllegalStateException("x");
        coordinator.submitInOrder("conv1", t);
        await(t);
        awaitIdle();
        List<DeadLetterEntry> entries = coordinator.getDeadLetters();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).id().startsWith("local-"));
        assertTrue(coordinator.discardDeadLetter(entries.get(0).id()));
    }

    @Test
    @DisplayName("discard, replay-acknowledge and per-conversation purge act on the shared stream")
    void deadLetterAdmin() {
        store.append("conv1", "e1", 1, Map.of("input", "a", "agentId", "x"));
        store.append("conv2", "e2", 2, null);
        store.append("conv1", "e3", 3, null);
        assertEquals(3, coordinator.getDeadLetters().size());
        assertTrue(coordinator.discardDeadLetter("2"));
        assertFalse(coordinator.discardDeadLetter("2"));
        assertEquals(2, coordinator.purgeDeadLetters("conv1"));
        assertFalse(coordinator.replayDeadLetter("1"), "already purged");
        assertEquals(0, coordinator.getDeadLetters().size());
    }

    @Test
    @DisplayName("the lease is never requested while the submitting thread holds a queue monitor long enough to matter")
    void submitReturnsBeforeLeaseCompletes() {
        leases.grantImmediately = false;
        AtomicBoolean returned = new AtomicBoolean();
        coordinator.submitInOrder("conv1", new Turn("t", log));
        returned.set(true);
        assertTrue(returned.get());
        assertEquals(1, leases.pending.size(), "submit registered the acquire and returned without waiting for it");
    }

    @Test
    @DisplayName("with the in-memory lease manager the coordinator behaves like the single-node one")
    void localLeasesBehaveLikeInMemory() throws Exception {
        var local = new ClusterConversationCoordinator(runtime, new SimpleMeterRegistry(), new LocalLeaseManager(), store,
                mock(NatsConnectionManager.class), mock(ClusterPresence.class), 10_000, 1000, Duration.ofSeconds(1));
        Turn a = new Turn("a", log);
        Turn b = new Turn("b", log);
        local.submitInOrder("c", a);
        local.submitInOrder("c", b);
        await(a);
        await(b);
        assertEquals(List.of("a", "b"), log);
        assertNull(a.lease.get().fence());
    }

    @Test
    @DisplayName("dead letters kept locally while NATS was down move to the shared stream on reconnect")
    void localDeadLettersAreForwardedOnReconnect() throws Exception {
        store.down = true;
        Turn failing = new Turn("boom", log);
        failing.failWith = new IllegalStateException("x");
        coordinator.submitInOrder("conv-local", failing);
        await(failing);
        awaitIdle();
        assertTrue(coordinator.getDeadLetters().get(0).id().startsWith("local-"), "kept node-locally while NATS is down");

        assertEquals(0, coordinator.forwardLocalDeadLetters(), "nothing moves while the stream is still unreachable");
        store.down = false;
        assertEquals(1, coordinator.forwardLocalDeadLetters());

        assertEquals(1, store.entries.size(), "now in the shared stream");
        List<DeadLetterEntry> listed = coordinator.getDeadLetters();
        assertEquals(1, listed.size(), "and no longer kept locally");
        assertFalse(listed.get(0).id().startsWith("local-"));
    }
}
