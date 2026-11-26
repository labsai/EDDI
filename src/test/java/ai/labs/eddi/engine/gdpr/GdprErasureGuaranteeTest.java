/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.grants.InMemoryConnectionGrantStore;
import ai.labs.eddi.connections.oauth.IOAuthStateStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureMetrics;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.Kind;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.PendingRemoval;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The erasure guarantee in the cascade: tombstones before any delete, the drain
 * barrier in the stop step (local and cluster), and a participant whose
 * verification keeps the erasure failed until a re-run finishes it.
 */
@DisplayName("GDPR erasure — the guarantee for asynchronously written rows")
class GdprErasureGuaranteeTest {

    private static final String USER_ID = "erased-user";
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    /** An async writer whose user rows live in a map: row id → user id. */
    static class Writer implements AsyncErasureWriter {
        final Map<String, String> rows = new HashMap<>();
        /**
         * Rows that carry only a conversation id, no user reference: row id →
         * conversation.
         */
        final Map<String, String> conversationRows = new HashMap<>();
        boolean drains = true;
        boolean failRemoval;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean awaitDrained(Duration timeout) {
            return drains;
        }

        @Override
        public void removeRow(String rowId) {
            if (failRemoval) {
                throw new IllegalStateException("row store down");
            }
            rows.remove(rowId);
        }

        @Override
        public long eraseConversation(String conversationId) {
            int before = conversationRows.size();
            conversationRows.values().removeIf(conversationId::equals);
            return before - conversationRows.size();
        }

        @Override
        public long countForConversation(String conversationId) {
            return conversationRows.values().stream().filter(conversationId::equals).count();
        }
    }

    private IUserMemoryStore userMemoryStore;
    private IConversationMemoryStore conversationMemoryStore;
    private InMemoryErasureTombstoneStore tombstones;
    private Writer writer;
    private ErasureGuard guard;
    private GdprComplianceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        userMemoryStore = mock(IUserMemoryStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1", "conv-2"));
        Instance<IConnectionGrantStore> grants = mock(Instance.class);
        when(grants.isResolvable()).thenReturn(true);
        when(grants.get()).thenReturn(new InMemoryConnectionGrantStore());
        Instance<IOAuthStateStore> states = mock(Instance.class);
        service = new GdprComplianceService(userMemoryStore, conversationMemoryStore, mock(IUserConversationStore.class), mock(IDatabaseLogs.class),
                mock(IAuditStore.class), mock(AuditLedgerService.class), mock(Instance.class), mock(IHitlToolJournalStore.class),
                mock(IConversationDescriptorStore.class), mock(IConversationCheckpointStore.class), mock(Instance.class), mock(Instance.class),
                mock(IScheduleStore.class), grants, states, List.of(), new CacheFactory(), 0L);
        tombstones = new InMemoryErasureTombstoneStore();
        writer = new Writer();
        guard = new ErasureGuard(tombstones, List.of(writer), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        service.erasureGuard = guard;
    }

    /** The writer's participant: deletes the user's rows, then verifies. */
    @SuppressWarnings("unchecked")
    private void registerParticipant() {
        IGdprParticipant participant = new IGdprParticipant() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public long erase(String userId) {
                long before = writer.rows.size();
                writer.rows.values().removeIf(userId::equals);
                guard.verifyUserErased("fake", userId, () -> writer.rows.values().stream().filter(userId::equals).count());
                return before - writer.rows.size();
            }

            @Override
            public Object export(String userId) {
                return null;
            }
        };
        Instance<IGdprParticipant> instance = mock(Instance.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doAnswer(inv -> List.of(participant).iterator()).when(instance).iterator();
        service.participantInstances = instance;
    }

    @Test
    @DisplayName("the user and each of their conversations are tombstoned before anything is deleted")
    void tombstonesBeforeDeletes() throws Exception {
        List<Boolean> tombstonedWhenDeleting = new ArrayList<>();
        doAnswer(inv -> {
            tombstonedWhenDeleting.add(tombstones.hasTombstone(Kind.USER, guard.userKey(KvKeys.sha256(USER_ID)))
                    && tombstones.hasTombstone(Kind.CONVERSATION, "conv-1") && tombstones.hasTombstone(Kind.CONVERSATION, "conv-2"));
            return null;
        }).when(userMemoryStore).deleteAllForUser(USER_ID);

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.complete(), result.failedSteps().toString());
        // Step 1 and the re-sweep (step 12) both delete memories; both saw the
        // tombstones.
        assertFalse(tombstonedWhenDeleting.isEmpty());
        assertFalse(tombstonedWhenDeleting.contains(false), tombstonedWhenDeleting.toString());
    }

    @Test
    @DisplayName("a tombstone that cannot be written is a failed step, never a clean erasure")
    void tombstoneFailure() {
        tombstones.failWrites = true;
        var result = service.deleteUserData(USER_ID);
        assertTrue(result.failedSteps().contains("erasureTombstone"), result.failedSteps().toString());
        assertFalse(result.complete());
    }

    @Test
    @DisplayName("single node: a writer that does not drain in time fails the erasure (asyncWriteDrain)")
    void localDrainTimeout() throws Exception {
        writer.drains = false;
        var result = service.deleteUserData(USER_ID);
        // The snapshots are kept: they are how a re-run finds the conversations of a
        // late write that could only record a conversation-scoped pending removal.
        assertEquals(List.of("asyncWriteDrain", "conversations"), result.failedSteps());
        verify(conversationMemoryStore, never()).deleteConversationsByUserId(USER_ID);
    }

    @Test
    @DisplayName("after a drain that timed out, a late conversation-only pending removal keeps the re-run failed until it is cleared")
    void lateConversationOnlyRemovalAfterADrainTimeout() throws Exception {
        registerParticipant();
        // The store as it behaves: once the snapshots are deleted, the user lists no
        // conversation any more.
        AtomicBoolean snapshotsDeleted = new AtomicBoolean();
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenAnswer(inv -> snapshotsDeleted.get() ? List.of() : List.of("conv-1", "conv-2"));
        doAnswer(inv -> {
            snapshotsDeleted.set(true);
            return 2L;
        }).when(conversationMemoryStore).deleteConversationsByUserId(USER_ID);
        writer.drains = false;
        var first = service.deleteUserData(USER_ID);
        assertTrue(first.failedSteps().contains("conversations"), first.failedSteps().toString());

        // A write that was still queued lands now, carries only conv-2, and cannot
        // remove itself: the user's own last check never counts that record.
        writer.conversationRows.put("late", "conv-2");
        writer.failRemoval = true;
        assertTrue(guard.afterWrite("fake", "late", null, "conv-2", NOW.minusSeconds(1), () -> {
            throw new IllegalStateException("row store down");
        }));
        writer.drains = true;

        var second = service.deleteUserData(USER_ID);
        assertFalse(second.complete(), "the conversation's pending removal is found through the kept snapshots");
        assertTrue(second.failedSteps().contains("asyncWriteConversations"), second.failedSteps().toString());
        verify(conversationMemoryStore, never()).deleteConversationsByUserId(USER_ID);

        writer.failRemoval = false;
        var third = service.deleteUserData(USER_ID);
        assertTrue(third.complete(), third.failedSteps().toString());
        verify(conversationMemoryStore).deleteConversationsByUserId(USER_ID);
    }

    @Test
    @DisplayName("cluster: a node whose barrier timed out reports it, and the cluster stop fails")
    void clusterDrainTimeout() {
        IClusterRpc rpc = mock(IClusterRpc.class);
        when(rpc.isClustered()).thenReturn(true);
        when(rpc.expectedPeerIds()).thenReturn(Set.of("n2"));
        when(rpc.callAllAwaiting(eq(IClusterRpc.GDPR_STOP), anyMap(), any(Duration.class), anySet())).thenReturn(
                Map.of("n2", Map.of("stopped", 0, "drained", false, "error", "async writer(s) not drained within PT30S: alerts")));
        service.clusterRpc = rpc;

        assertEquals(List.of("clusterStop", "conversations"), service.deleteUserData(USER_ID).failedSteps());
    }

    @Test
    @DisplayName("cluster: a node that joined after the peers were read cannot confirm the stop in place of one that did not answer")
    void clusterSubstituteReplyDoesNotCount() throws Exception {
        IClusterRpc rpc = mock(IClusterRpc.class);
        when(rpc.isClustered()).thenReturn(true);
        when(rpc.expectedPeerIds()).thenReturn(Set.of("n2", "n3"));
        when(rpc.callAllAwaiting(eq(IClusterRpc.GDPR_STOP), anyMap(), any(Duration.class), eq(Set.of("n2", "n3"))))
                .thenReturn(Map.of("n2", Map.of("stopped", 0, "drained", true), "n4", Map.of("stopped", 0, "drained", true)));
        service.clusterRpc = rpc;

        assertEquals(List.of("clusterStop", "conversations"), service.deleteUserData(USER_ID).failedSteps());
        verify(conversationMemoryStore, never()).deleteConversationsByUserId(USER_ID);
    }

    @Test
    @DisplayName("cluster: a node that stopped but did not confirm a drain (an older release) fails the cluster stop")
    void clusterNoDrainConfirmation() {
        IClusterRpc rpc = mock(IClusterRpc.class);
        when(rpc.isClustered()).thenReturn(true);
        when(rpc.expectedPeerIds()).thenReturn(Set.of("n2"));
        when(rpc.callAllAwaiting(eq(IClusterRpc.GDPR_STOP), anyMap(), any(Duration.class), anySet())).thenReturn(Map.of("n2", Map.of("stopped", 0)));
        service.clusterRpc = rpc;

        assertEquals(List.of("clusterStop", "conversations"), service.deleteUserData(USER_ID).failedSteps());
    }

    @Test
    @DisplayName("cluster: every node drained — clean, and the wait covers the stop timeout plus the drain timeout")
    void clusterDrained() {
        IClusterRpc rpc = mock(IClusterRpc.class);
        when(rpc.isClustered()).thenReturn(true);
        when(rpc.expectedPeerIds()).thenReturn(Set.of("n2"));
        when(rpc.callAllAwaiting(eq(IClusterRpc.GDPR_STOP), anyMap(), any(Duration.class), anySet()))
                .thenReturn(Map.of("n2", Map.of("stopped", 0, "drained", true)));
        service.clusterRpc = rpc;

        assertTrue(service.deleteUserData(USER_ID).complete());
        // The request says the erasure wrote tombstones, so a peer can flag one that
        // did not.
        verify(rpc).callAllAwaiting(eq(IClusterRpc.GDPR_STOP), argThat(request -> Boolean.TRUE.equals(request.get(GdprComplianceService.TOMBSTONED))),
                eq(Duration.ofSeconds(40)), eq(Set.of("n2")));
    }

    @Test
    @DisplayName("a row that names only one of the user's conversations, not the user, is erased with the conversation")
    void conversationOnlyRowsAreErased() {
        registerParticipant();
        writer.conversationRows.put("c-row", "conv-2");
        writer.conversationRows.put("other-row", "conv-of-someone-else");

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.complete(), result.failedSteps().toString());
        assertEquals(Map.of("other-row", "conv-of-someone-else"), writer.conversationRows);
    }

    @Test
    @DisplayName("a conversation the user started after the tombstones were written is tombstoned before its rows are erased")
    void conversationStartedMeanwhileIsTombstoned() throws Exception {
        registerParticipant();
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"), List.of("conv-1", "conv-new"));

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.complete(), result.failedSteps().toString());
        assertTrue(tombstones.hasTombstone(Kind.CONVERSATION, "conv-new"), "a late write to it is refused too");
    }

    @Test
    @DisplayName("a conversation whose async rows cannot be erased is kept for the re-run, and the erasure fails")
    void conversationRowsThatStayKeepTheConversation() throws Exception {
        registerParticipant();
        Writer stuck = new Writer() {
            @Override
            public long eraseConversation(String conversationId) {
                return 0;
            }
        };
        stuck.conversationRows.put("c-row", "conv-1");
        guard = new ErasureGuard(tombstones, List.of(stuck), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        service.erasureGuard = guard;

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.failedSteps().contains("asyncWriteConversations"), result.failedSteps().toString());
        assertTrue(result.failedSteps().contains("conversations"), "the snapshots are what a re-run finds the conversations by");
        verify(conversationMemoryStore, never()).deleteConversationsByUserId(USER_ID);
    }

    @Test
    @DisplayName("a pending removal of the user by a writer without a participant (or no longer deployed) fails the erasure")
    void pendingRemovalOfAnotherWriter() {
        registerParticipant();
        tombstones.addPendingRemoval(new PendingRemoval("retired", "r9", guard.userKey(KvKeys.sha256(USER_ID)), null, NOW));

        var result = service.deleteUserData(USER_ID);

        assertEquals(List.of("asyncWritePending"), result.failedSteps());
    }

    @Test
    @DisplayName("a row whose self-removal failed keeps the erasure failed; the re-run, once the removal works, completes it")
    void idempotentRerunFinishesAFailedErasure() {
        registerParticipant();
        // A write that passed beforeWrite just before the tombstone, landed after it,
        // and could not remove itself: a pending removal is left.
        assertTrue(guard.beforeWrite(USER_ID, "conv-1", NOW.minusSeconds(1)));
        writer.rows.put("r1", USER_ID);
        guard.tombstoneUser(USER_ID, List.of("conv-1"));
        writer.failRemoval = true;
        assertTrue(guard.afterWrite("fake", "r1", USER_ID, "conv-1", NOW.minusSeconds(1), () -> writer.removeRow("r1")));
        // The participant's own delete keeps working (it is another path), but the
        // pending removal is not cleared while the removal fails.
        writer.rows.put("r1", USER_ID);

        var first = service.deleteUserData(USER_ID);
        // The pending removal names conv-1 as well, so the conversation is kept for the
        // re-run (asyncWriteConversations, conversations); the participant's own
        // check (fake) and the last check over every writer (asyncWritePending) fail.
        assertEquals(List.of("asyncWriteConversations", "conversations", "fake", "asyncWritePending"), first.failedSteps(),
                "a pending removal must not be reported as a clean erasure");

        writer.failRemoval = false;
        var second = service.deleteUserData(USER_ID);
        assertTrue(second.complete(), second.failedSteps().toString());
        assertEquals(0, tombstones.pendingCount());
        assertTrue(writer.rows.isEmpty());
    }
}
