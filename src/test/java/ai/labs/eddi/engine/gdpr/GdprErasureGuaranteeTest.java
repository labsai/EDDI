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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
            return 0;
        }

        @Override
        public long countForConversation(String conversationId) {
            return 0;
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
    void localDrainTimeout() {
        writer.drains = false;
        var result = service.deleteUserData(USER_ID);
        assertEquals(List.of("asyncWriteDrain"), result.failedSteps());
    }

    @Test
    @DisplayName("cluster: a node whose barrier timed out reports it, and the cluster stop fails")
    void clusterDrainTimeout() {
        IClusterRpc rpc = mock(IClusterRpc.class);
        when(rpc.isClustered()).thenReturn(true);
        when(rpc.expectedPeers()).thenReturn(1);
        when(rpc.callAll(eq(IClusterRpc.GDPR_STOP), anyMap(), any(Duration.class), anyInt())).thenReturn(
                Map.of("n2", Map.of("stopped", 0, "drained", false, "error", "async writer(s) not drained within PT30S: alerts")));
        service.clusterRpc = rpc;

        assertEquals(List.of("clusterStop"), service.deleteUserData(USER_ID).failedSteps());
    }

    @Test
    @DisplayName("cluster: a node that stopped but did not confirm a drain (an older release) fails the cluster stop")
    void clusterNoDrainConfirmation() {
        IClusterRpc rpc = mock(IClusterRpc.class);
        when(rpc.isClustered()).thenReturn(true);
        when(rpc.expectedPeers()).thenReturn(1);
        when(rpc.callAll(eq(IClusterRpc.GDPR_STOP), anyMap(), any(Duration.class), anyInt())).thenReturn(Map.of("n2", Map.of("stopped", 0)));
        service.clusterRpc = rpc;

        assertEquals(List.of("clusterStop"), service.deleteUserData(USER_ID).failedSteps());
    }

    @Test
    @DisplayName("cluster: every node drained — clean, and the wait covers the stop timeout plus the drain timeout")
    void clusterDrained() {
        IClusterRpc rpc = mock(IClusterRpc.class);
        when(rpc.isClustered()).thenReturn(true);
        when(rpc.expectedPeers()).thenReturn(1);
        when(rpc.callAll(eq(IClusterRpc.GDPR_STOP), anyMap(), any(Duration.class), anyInt()))
                .thenReturn(Map.of("n2", Map.of("stopped", 0, "drained", true)));
        service.clusterRpc = rpc;

        assertTrue(service.deleteUserData(USER_ID).complete());
        verify(rpc).callAll(eq(IClusterRpc.GDPR_STOP), anyMap(), eq(Duration.ofSeconds(40)), eq(1));
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
        assertEquals(List.of("fake"), first.failedSteps(), "a pending removal must not be reported as a clean erasure");

        writer.failRemoval = false;
        var second = service.deleteUserData(USER_ID);
        assertTrue(second.complete(), second.failedSteps().toString());
        assertEquals(0, tombstones.pendingCount());
        assertTrue(writer.rows.isEmpty());
    }
}
