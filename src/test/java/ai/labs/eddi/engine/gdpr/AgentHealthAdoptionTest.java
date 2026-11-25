/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.grants.InMemoryConnectionGrantStore;
import ai.labs.eddi.connections.oauth.IOAuthStateStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.gdpr.adoption.HealthShapedWriter;
import ai.labs.eddi.engine.gdpr.adoption.HealthShapedWriter.Row;
import ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.ErasureIncompleteException;
import ai.labs.eddi.engine.gdpr.erasure.ErasureMetrics;
import ai.labs.eddi.engine.gdpr.erasure.InMemoryErasureTombstoneStore;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationCheckpointStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Can Agent Health give the erasure guarantee? These tests run a writer shaped
 * like its turn recorder ({@link HealthShapedWriter}: one worker, a bounded
 * queue, rows with the plain user id that are deleted, not blanked) through the
 * real erasure cascade and the conversation-delete path, against queued and
 * in-flight writes, a node the barrier cannot reach, and a store that fails.
 * The writer uses only the public erasure API, from a package of its own.
 */
@DisplayName("Erasure guarantee — adopted by an Agent-Health-shaped writer")
class AgentHealthAdoptionTest {

    private static final String USER = "erased-user";
    private static final String CONVERSATION = "conv-1";

    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    private InMemoryErasureTombstoneStore tombstones;
    private ErasureGuard guard;
    private HealthShapedWriter writer;
    private GdprComplianceService service;
    private ExecutorService erasures;
    private final List<HealthShapedWriter> toClose = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        tombstones = new InMemoryErasureTombstoneStore();
        List<AsyncErasureWriter> writers = new ArrayList<>();
        guard = newGuard(writers);
        writer = node(guard);
        writers.add(writer);

        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        when(conversations.getConversationIdsByUserId(USER)).thenReturn(List.of(CONVERSATION));
        Instance<IConnectionGrantStore> grants = mock(Instance.class);
        when(grants.isResolvable()).thenReturn(true);
        when(grants.get()).thenReturn(new InMemoryConnectionGrantStore());
        service = new GdprComplianceService(mock(IUserMemoryStore.class), conversations, mock(IUserConversationStore.class),
                mock(IDatabaseLogs.class), mock(IAuditStore.class), mock(AuditLedgerService.class), mock(Instance.class),
                mock(IHitlToolJournalStore.class), mock(IConversationDescriptorStore.class), mock(IConversationCheckpointStore.class),
                mock(Instance.class), mock(Instance.class), mock(IScheduleStore.class), grants, mock(Instance.class), List.of(),
                new CacheFactory(), 0L);
        service.erasureGuard = guard;
        IGdprParticipant participant = writer.participant();
        Instance<IGdprParticipant> participants = mock(Instance.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doAnswer(inv -> List.of(participant).iterator()).when(participants).iterator();
        service.participantInstances = participants;
        erasures = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        erasures.shutdownNow();
        for (HealthShapedWriter w : toClose) {
            w.holdBeforeInsert = null;
            w.close();
        }
    }

    private ErasureGuard newGuard(Iterable<AsyncErasureWriter> writers) {
        return new ErasureGuard(tombstones, writers, new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400), Duration.ofSeconds(5),
                Duration.ofMinutes(5), Clock.systemUTC());
    }

    /** A node: a writer over the shared rows, guarded by {@code nodeGuard}. */
    private HealthShapedWriter node(ErasureGuard nodeGuard) {
        HealthShapedWriter w = new HealthShapedWriter(rows, nodeGuard, 16);
        toClose.add(w);
        return w;
    }

    /** A node the erasing node's barrier cannot reach (cut off from NATS). */
    private HealthShapedWriter unreachableNode() {
        List<AsyncErasureWriter> own = new ArrayList<>();
        ErasureGuard ownGuard = newGuard(own);
        HealthShapedWriter w = node(ownGuard);
        own.add(w);
        return w;
    }

    private long rowsOf(String userId) {
        return rows.values().stream().filter(r -> userId.equals(r.userId())).count();
    }

    private void drained(HealthShapedWriter w) throws InterruptedException {
        assertTrue(w.awaitDrained(Duration.ofSeconds(5)), "the writer did not drain");
    }

    @Test
    @DisplayName("user erasure: a turn captured before the erasure but queued after it is never written")
    void capturedBeforeQueuedAfter() throws Exception {
        Instant captured = Instant.now().minusSeconds(1);
        rows.put("old", new Row("old", USER, CONVERSATION));

        var result = service.deleteUserData(USER);
        assertTrue(result.complete(), result.failedSteps().toString());

        assertTrue(writer.record("late", USER, CONVERSATION, captured));
        drained(writer);
        assertEquals(0, rowsOf(USER), "the tombstone refused the late write");
        assertEquals(0, writer.inserted.get(), "refused before the insert, not removed after it");
    }

    @Test
    @DisplayName("user erasure: the erasure does not finish while a write that passed its check is still in flight")
    void erasureWaitsForTheWriteInFlight() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        writer.holdBeforeInsert = hold;
        writer.reachedHold = new CountDownLatch(1);
        assertTrue(writer.record("in-flight", USER, CONVERSATION, Instant.now()));
        assertTrue(writer.reachedHold.await(5, TimeUnit.SECONDS));

        Future<GdprDeletionResult> erasure = erasures.submit(() -> service.deleteUserData(USER));
        Thread.sleep(300);
        assertFalse(erasure.isDone(), "the drain barrier holds the erasure until the in-flight write is done");

        hold.countDown();
        var result = erasure.get(10, TimeUnit.SECONDS);
        assertTrue(result.complete(), result.failedSteps().toString());
        assertEquals(0, rowsOf(USER));
    }

    @Test
    @DisplayName("user erasure: a write in flight on a node the barrier cannot reach removes itself when it lands")
    void unreachableNodeRemovesItsLateRow() throws Exception {
        HealthShapedWriter cutOff = unreachableNode();
        CountDownLatch hold = new CountDownLatch(1);
        cutOff.holdBeforeInsert = hold;
        cutOff.reachedHold = new CountDownLatch(1);
        assertTrue(cutOff.record("cut-off", USER, CONVERSATION, Instant.now()));
        assertTrue(cutOff.reachedHold.await(5, TimeUnit.SECONDS));

        var result = service.deleteUserData(USER);
        assertTrue(result.complete(), "residual limit 1: the barrier cannot see that node — " + result.failedSteps());

        hold.countDown();
        drained(cutOff);
        assertEquals(0, rowsOf(USER), "afterWrite found the tombstone and deleted the row it had just written");
    }

    @Test
    @DisplayName("user erasure: a late row that cannot delete itself keeps every re-run failed until it is gone, then one completes")
    void undeletableLateRowFailsUntilGone() throws Exception {
        HealthShapedWriter cutOff = unreachableNode();
        CountDownLatch hold = new CountDownLatch(1);
        cutOff.holdBeforeInsert = hold;
        cutOff.reachedHold = new CountDownLatch(1);
        assertTrue(cutOff.record("cut-off", USER, CONVERSATION, Instant.now()));
        assertTrue(cutOff.reachedHold.await(5, TimeUnit.SECONDS));
        assertTrue(service.deleteUserData(USER).complete());

        // The row lands, and the store (shared by both nodes) refuses the delete that
        // should follow it: a durable pending removal is recorded.
        cutOff.failDeletes = true;
        writer.failDeletes = true;
        hold.countDown();
        drained(cutOff);
        assertEquals(1, rowsOf(USER), "in the window residual limit 1 describes, the row exists");
        assertEquals(1, tombstones.pendingCount(), "recorded for a retry");

        var rerun = service.deleteUserData(USER);
        assertFalse(rerun.complete(), "a re-run cannot pass while the row may still exist");
        assertTrue(rerun.failedSteps().contains(HealthShapedWriter.NAME), rerun.failedSteps().toString());

        writer.failDeletes = false;
        cutOff.failDeletes = false;
        var finished = service.deleteUserData(USER);
        assertTrue(finished.complete(), finished.failedSteps().toString());
        assertEquals(0, rowsOf(USER));
        assertEquals(0, tombstones.pendingCount());
    }

    @Test
    @DisplayName("user erasure: a store that fails during the erasure is a failed step, never a clean erasure")
    void storeFailureFailsTheStep() {
        rows.put("old", new Row("old", USER, CONVERSATION));
        writer.storeDown = true;
        var result = service.deleteUserData(USER);
        assertFalse(result.complete());
        assertTrue(result.failedSteps().contains(HealthShapedWriter.NAME), result.failedSteps().toString());
        assertTrue(result.failedSteps().contains("asyncWriteConversations"), result.failedSteps().toString());
    }

    @Test
    @DisplayName("conversation delete: a turn queued before the delete never lands, whenever it was captured")
    void conversationDeleteRefusesQueuedTurns() throws Exception {
        rows.put("old", new Row("old", USER, CONVERSATION));
        guard.eraseConversation(CONVERSATION);
        assertEquals(0, writer.countForConversation(CONVERSATION));

        assertTrue(writer.record("late", USER, CONVERSATION, Instant.now().plusSeconds(3600)));
        drained(writer);
        assertEquals(0, writer.countForConversation(CONVERSATION), "the conversation tombstone has no time component");
        assertEquals(0, writer.inserted.get(), "refused before the insert");
    }

    @Test
    @DisplayName("conversation delete: a store that fails keeps the delete failed until a retry succeeds")
    void conversationDeleteFailsWhileTheStoreIsDown() throws Exception {
        rows.put("old", new Row("old", USER, CONVERSATION));
        writer.storeDown = true;
        assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
        writer.storeDown = false;
        guard.eraseConversation(CONVERSATION);
        assertEquals(0, writer.countForConversation(CONVERSATION));
    }

    @Test
    @DisplayName("a full queue drops the turn (AbortPolicy) and never holds a barrier")
    void fullQueueNeverHoldsTheBarrier() throws Exception {
        HealthShapedWriter tiny = new HealthShapedWriter(rows, guard, 1);
        toClose.add(tiny);
        CountDownLatch hold = new CountDownLatch(1);
        tiny.holdBeforeInsert = hold;
        tiny.reachedHold = new CountDownLatch(1);
        assertTrue(tiny.record("a", "someone", "c-a", Instant.now()));
        assertTrue(tiny.reachedHold.await(5, TimeUnit.SECONDS));
        assertTrue(tiny.record("b", "someone", "c-b", Instant.now()), "queued");
        assertFalse(tiny.record("c", "someone", "c-c", Instant.now()), "rejected: the queue holds one");
        hold.countDown();
        assertTrue(tiny.awaitDrained(Duration.ofSeconds(5)), "the rejected task is not waited for");
    }
}
