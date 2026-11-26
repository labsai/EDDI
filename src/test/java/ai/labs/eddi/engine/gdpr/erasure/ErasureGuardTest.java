/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.ErasureIncompleteException;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ErasureGuard — the erasure guarantee for async writers")
class ErasureGuardTest {

    static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String USER = "user-7f3a";
    private static final String CONVERSATION = "conv-41";

    /** A writer whose rows live in a map: row id → conversation id. */
    static class FakeWriter implements AsyncErasureWriter {
        final Map<String, String> rows = new HashMap<>();
        boolean drains = true;
        boolean failRemoval;
        final List<String> removed = new ArrayList<>();
        int erasedConversations;
        int drainCalls;
        /** A conversation whose rows this writer cannot erase (its store refuses). */
        String stuckConversation;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean awaitDrained(Duration timeout) {
            drainCalls++;
            return drains;
        }

        @Override
        public void removeRow(String rowId) {
            if (failRemoval) {
                throw new IllegalStateException("row store down");
            }
            rows.remove(rowId);
            removed.add(rowId);
        }

        @Override
        public long eraseConversation(String conversationId) {
            erasedConversations++;
            if (conversationId.equals(stuckConversation)) {
                return 0;
            }
            int before = rows.size();
            rows.values().removeIf(conversationId::equals);
            return before - rows.size();
        }

        @Override
        public long countForConversation(String conversationId) {
            return rows.values().stream().filter(conversationId::equals).count();
        }
    }

    private InMemoryErasureTombstoneStore store;
    private FakeWriter writer;
    private ErasureGuard guard;
    private Instant clockNow;

    @BeforeEach
    void setUp() {
        store = new InMemoryErasureTombstoneStore();
        writer = new FakeWriter();
        clockNow = NOW;
        guard = new ErasureGuard(store, List.of(writer), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), new Clock() {
                    @Override
                    public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return clockNow;
                    }
                });
    }

    /** A guarded write the way an async writer does it. */
    private boolean write(String rowId, Instant capturedAt, Runnable betweenCheckAndWrite) {
        if (!guard.beforeWrite(USER, CONVERSATION, capturedAt)) {
            return false;
        }
        betweenCheckAndWrite.run();
        writer.rows.put(rowId, CONVERSATION);
        return !guard.afterWrite(writer.name(), rowId, USER, CONVERSATION, capturedAt, () -> writer.removeRow(rowId));
    }

    @Nested
    @DisplayName("beforeWrite")
    class BeforeWrite {

        @Test
        @DisplayName("allows a write when nothing was erased")
        void allows() {
            assertTrue(guard.beforeWrite(USER, CONVERSATION, NOW));
        }

        @Test
        @DisplayName("a queued write of an erased conversation is blocked, whenever it was captured")
        void conversationTombstone() {
            guard.tombstoneConversation(CONVERSATION);
            assertFalse(guard.beforeWrite("someone", CONVERSATION, NOW.plus(Duration.ofDays(30))));
        }

        @Test
        @DisplayName("a queued write captured before the user's erasure is blocked; one captured later (new activity) is not")
        void userTombstone() {
            guard.tombstoneUser(USER, List.of());
            assertFalse(guard.beforeWrite(USER, "other-conv", NOW.minusSeconds(30)));
            assertFalse(guard.beforeWrite(USER, "other-conv", NOW.plus(Duration.ofMinutes(4))), "within the clock-skew tolerance");
            assertTrue(guard.beforeWrite(USER, "other-conv", NOW.plus(Duration.ofMinutes(6))));
            assertFalse(guard.beforeWrite(USER, "other-conv", null), "unknown capture time: blocked");
            assertTrue(guard.beforeWrite("someone-else", "other-conv", NOW.minusSeconds(30)));
        }

        @Test
        @DisplayName("the user's conversations are tombstoned with the user")
        void userConversations() {
            guard.tombstoneUser(USER, List.of(CONVERSATION));
            assertFalse(guard.beforeWrite(null, CONVERSATION, NOW.plus(Duration.ofDays(1))));
        }

        @Test
        @DisplayName("fails closed when the tombstones cannot be read")
        void failsClosed() {
            store.failReads = true;
            assertFalse(guard.beforeWrite(USER, CONVERSATION, NOW));
        }

        @Test
        @DisplayName("a tombstone past its retention no longer blocks")
        void expiry() {
            guard.tombstoneConversation(CONVERSATION);
            clockNow = NOW.plus(Duration.ofDays(400));
            assertTrue(guard.beforeWrite(USER, CONVERSATION, NOW));
            assertEquals(1, guard.deleteExpiredTombstones());
        }
    }

    @Nested
    @DisplayName("afterWrite")
    class AfterWrite {

        @Test
        @DisplayName("an erasure that commits between the check and the write: the row removes itself")
        void selfDeletes() {
            boolean kept = write("r1", NOW.minusSeconds(1), () -> guard.tombstoneUser(USER, List.of(CONVERSATION)));
            assertFalse(kept);
            assertFalse(writer.rows.containsKey("r1"));
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("no erasure: the row stays and nothing is removed")
        void keeps() {
            assertTrue(write("r1", NOW, () -> {
            }));
            assertTrue(writer.rows.containsKey("r1"));
            assertTrue(writer.removed.isEmpty());
        }

        @Test
        @DisplayName("a failed self-removal leaves a durable pending removal; the user's verification fails until it is cleared")
        void pendingUntilCleared() {
            writer.failRemoval = true;
            assertFalse(write("r1", NOW.minusSeconds(1), () -> guard.tombstoneUser(USER, List.of(CONVERSATION))));
            assertEquals(1, store.pendingCount());

            IllegalStateException failed = assertThrows(IllegalStateException.class, () -> guard.verifyUserErased("fake", USER, () -> 0));
            assertTrue(failed.getMessage().contains("1 pending removal"), failed.getMessage());

            // The re-run of the erasure: the removal now works, the verification passes.
            writer.failRemoval = false;
            guard.verifyUserErased("fake", USER, () -> 0);
            assertEquals(0, store.pendingCount());
            assertFalse(writer.rows.containsKey("r1"));
        }

        @Test
        @DisplayName("the maintenance pass clears a pending removal without a re-run")
        void maintenanceRetries() {
            writer.failRemoval = true;
            write("r1", NOW.minusSeconds(1), () -> guard.tombstoneConversation(CONVERSATION));
            writer.failRemoval = false;
            new ErasureGuardMaintenance(guard).run();
            assertEquals(0, store.pendingCount());
            assertFalse(writer.rows.containsKey("r1"));
        }

        @Test
        @DisplayName("a check that fails after the write removes the row (fail closed)")
        void checkFailsAfterWrite() {
            assertFalse(write("r1", NOW, () -> store.failReads = true));
            assertFalse(writer.rows.containsKey("r1"));
        }

        @Test
        @DisplayName("rows remaining fail the verification even without a pending removal")
        void remainingRows() {
            assertThrows(IllegalStateException.class, () -> guard.verifyUserErased("fake", USER, () -> 2));
        }

        @Test
        @DisplayName("a pending removal of a writer no bean implements stays pending")
        void unknownWriter() {
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("gone", "r9", null, CONVERSATION, NOW));
            assertEquals(0, guard.retryPending(null, null, null));
            assertEquals(1, store.pendingCount());
        }
    }

    @Nested
    @DisplayName("explicit conversation delete")
    class ConversationDelete {

        @Test
        @DisplayName("tombstones, drains, deletes and verifies")
        void deletes() throws Exception {
            writer.rows.put("r1", CONVERSATION);
            writer.rows.put("r2", "other");
            guard.eraseConversation(CONVERSATION);
            assertTrue(store.hasTombstone(Kind.CONVERSATION, CONVERSATION));
            assertEquals(Map.of("r2", "other"), writer.rows);
        }

        @Test
        @DisplayName("a writer that does not drain fails the delete before anything is deleted")
        void drainTimeout() {
            writer.drains = false;
            writer.rows.put("r1", CONVERSATION);
            assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
            assertEquals(0, writer.erasedConversations);
            assertTrue(store.hasTombstone(Kind.CONVERSATION, CONVERSATION), "the tombstone already blocks later writes");
        }

        @Test
        @DisplayName("a tombstone that cannot be written fails the delete")
        void tombstoneFails() {
            store.failWrites = true;
            assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
            assertEquals(0, writer.erasedConversations);
        }

        @Test
        @DisplayName("a pending removal of the conversation is retried; one that still fails fails the delete, a retry finishes it")
        void pendingRemovalFailsUntilRetried() throws Exception {
            writer.rows.put("r1", CONVERSATION);
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("fake", "r1", null, CONVERSATION, NOW));
            writer.failRemoval = true;
            assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
            writer.failRemoval = false;
            guard.eraseConversation(CONVERSATION);
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("cluster mode: the barrier is broadcast and every node must confirm the drain")
        void broadcast() throws Exception {
            IClusterRpc rpc = mock(IClusterRpc.class);
            when(rpc.isClustered()).thenReturn(true);
            when(rpc.expectedPeerIds()).thenReturn(Set.of("n2", "n3"));
            when(rpc.callAllAwaiting(eq(IClusterRpc.ERASURE_DRAIN), anyMap(), eq(Duration.ofSeconds(40)), eq(Set.of("n2", "n3"))))
                    .thenReturn(Map.of("n2", Map.of("drained", true), "n3", Map.of("drained", true)));
            guard.clusterRpc = rpc;
            writer.rows.put("r1", CONVERSATION);
            guard.eraseConversation(CONVERSATION);
            assertEquals(Map.of(), writer.rows, "every sampled node confirmed, so the rows were deleted");
            // The peers drain everything; the request names no conversation id, which
            // would only end up in their logs.
            verify(rpc).callAllAwaiting(eq(IClusterRpc.ERASURE_DRAIN), eq(Map.of("conversations", 1)), eq(Duration.ofSeconds(40)),
                    eq(Set.of("n2", "n3")));
        }

        @Test
        @DisplayName("cluster mode: a node that joined after the peers were read cannot confirm in place of one that did not answer")
        void substituteReplyDoesNotCount() {
            IClusterRpc rpc = mock(IClusterRpc.class);
            when(rpc.isClustered()).thenReturn(true);
            when(rpc.expectedPeerIds()).thenReturn(Set.of("n2", "n3"));
            when(rpc.callAllAwaiting(eq(IClusterRpc.ERASURE_DRAIN), anyMap(), eq(Duration.ofSeconds(40)), eq(Set.of("n2", "n3"))))
                    .thenReturn(Map.of("n2", Map.of("drained", true), "n4", Map.of("drained", true)));
            guard.clusterRpc = rpc;
            writer.rows.put("r1", CONVERSATION);
            ErasureIncompleteException missing = assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
            assertTrue(missing.getMessage().contains("1 of 2 node(s) did not confirm the drain: n3"), missing.getMessage());
            assertEquals(0, writer.erasedConversations, "nothing is deleted while n3 may still have a write queued");
        }

        @Test
        @DisplayName("the verdict: every sampled node must confirm; a reply from outside the sample confirms nothing but can still fail")
        void verdict() {
            assertEquals(List.of(), ErasureGuard.clusterVerdict(Map.of("a", Map.of("drained", true)), Set.of("a")));
            assertEquals(List.of("1 of 1 node(s) did not confirm the drain: a"),
                    ErasureGuard.clusterVerdict(Map.of("b", Map.of("drained", true)), Set.of("a")));
            assertEquals(List.of("node b: async writer(s) not drained"),
                    ErasureGuard.clusterVerdict(Map.of("a", Map.of("drained", true), "b", Map.of("drained", false, "error",
                            "async writer(s) not drained")), Set.of("a")));
        }

        @Test
        @DisplayName("cluster mode: a node that does not answer, or answers without draining, fails the delete before deleting")
        void broadcastIncomplete() {
            IClusterRpc rpc = mock(IClusterRpc.class);
            when(rpc.isClustered()).thenReturn(true);
            when(rpc.expectedPeerIds()).thenReturn(Set.of("n2", "n3"));
            AtomicInteger calls = new AtomicInteger();
            when(rpc.callAllAwaiting(eq(IClusterRpc.ERASURE_DRAIN), anyMap(), eq(Duration.ofSeconds(40)), eq(Set.of("n2", "n3"))))
                    .thenAnswer(inv -> calls.incrementAndGet() == 1
                            ? Map.of("n2", Map.of("drained", true))
                            : Map.of("n2", Map.of("drained", true), "n3", Map.of()));
            guard.clusterRpc = rpc;
            writer.rows.put("r1", CONVERSATION);
            ErasureIncompleteException missing = assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
            assertTrue(missing.getMessage().contains("did not confirm"), missing.getMessage());
            ErasureIncompleteException old = assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
            assertTrue(old.getMessage().contains("older release"), old.getMessage());
            assertEquals(0, writer.erasedConversations);
        }
    }

    @Nested
    @DisplayName("tombstone retention")
    class Retention {

        @Test
        @DisplayName("a retention shorter than the erasure that writes the tombstone is refused, whichever constructor builds the guard")
        void tooShortIsRefused() {
            ErasureMetrics metrics = new ErasureMetrics(new SimpleMeterRegistry());
            Duration minimum = Duration.ofDays(1).plus(Duration.ofSeconds(60)).plus(Duration.ofMinutes(5));
            assertEquals(minimum, ErasureGuard.minimumRetention(Duration.ofSeconds(30), Duration.ofMinutes(5)));
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new ErasureGuard(store, List.of(writer), metrics, Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofMinutes(5),
                            Clock.systemUTC()));
            assertTrue(refused.getMessage().contains("eddi.gdpr.tombstone-retention"), refused.getMessage());
            assertThrows(IllegalArgumentException.class, () -> new ErasureGuard(store, null, metrics, minimum.minusSeconds(1),
                    Duration.ofSeconds(30), Duration.ofMinutes(5)));
            new ErasureGuard(store, List.of(writer), metrics, minimum, Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.systemUTC());
        }

        @Test
        @DisplayName("a long drain timeout raises the minimum with it")
        void scalesWithTheDrainTimeout() {
            assertEquals(Duration.ofDays(5).plus(Duration.ofMinutes(5)), ErasureGuard.minimumRetention(Duration.ofDays(2), Duration.ofMinutes(5)));
        }
    }

    @Nested
    @DisplayName("the tombstone key")
    class TombstoneKey {

        private ErasureGuard guardWith(InMemoryErasureTombstoneStore shared, byte[] vaultSubkey) {
            ErasureGuard other = new ErasureGuard(shared, List.of(writer), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                    Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
            other.vaultKey = () -> vaultSubkey;
            return other;
        }

        @Test
        @DisplayName("with a vault the key comes from it: no salt is stored, and every node with the same vault agrees")
        void fromTheVault() {
            byte[] subkey = new byte[32];
            subkey[0] = 7;
            ErasureGuard a = guardWith(store, subkey);
            ErasureGuard b = guardWith(store, subkey);
            assertEquals(a.userKey("h"), b.userKey("h"));
            assertEquals(null, store.putMetaIfAbsent(ErasureGuard.SALT_META, null), "no salt was written");
            assertTrue(store.putMetaIfAbsent(ErasureGuard.KEY_CHECK_META, "x").startsWith("vault:"));
        }

        @Test
        @DisplayName("without a vault every node adopts the stored salt")
        void fromTheSalt() {
            ErasureGuard a = guardWith(store, null);
            ErasureGuard b = guardWith(store, null);
            assertEquals(a.userKey("h"), b.userKey("h"));
            assertTrue(store.putMetaIfAbsent(ErasureGuard.KEY_CHECK_META, "x").startsWith("salt:"));
        }

        @Test
        @DisplayName("a changed key (vault added later, salt lost) fails closed instead of silently not matching, and recovers once restored")
        void changedKeyFailsClosed() {
            ErasureGuard original = guardWith(store, null);
            original.tombstoneUser(USER, List.of());
            String salt = store.putMetaIfAbsent(ErasureGuard.SALT_META, "unused");

            // A vault configured after tombstones were written under the salt.
            ErasureGuard switched = guardWith(store, new byte[32]);
            assertThrows(IllegalStateException.class, () -> switched.userKey("h"));
            assertFalse(switched.beforeWrite(USER, "other", NOW.plus(Duration.ofDays(1))), "a guarded write is skipped");
            assertThrows(IllegalStateException.class, () -> switched.tombstoneUser("someone", List.of()), "an erasure step fails");
            assertThrows(IllegalStateException.class, () -> switched.verifyUserErased("fake", USER, () -> 0));

            // The salt row lost (a partial restore): a new salt would not match either.
            store.replaceMeta(ErasureGuard.SALT_META, Base64.getEncoder().encodeToString(new byte[32]));
            ErasureGuard lostSalt = guardWith(store, null);
            assertThrows(IllegalStateException.class, () -> lostSalt.userKey("h"));

            // Restored: the same guard recovers (a mismatch is never cached).
            store.replaceMeta(ErasureGuard.SALT_META, salt);
            assertFalse(lostSalt.beforeWrite(USER, "other", NOW.minusSeconds(1)), "the old tombstone matches again");
        }
    }

    @Test
    @DisplayName("clock skew: content captured up to erasedAt + skew is refused, a millisecond later it is new activity")
    void skewBoundary() {
        guard.tombstoneUser(USER, List.of());
        assertFalse(guard.beforeWrite(USER, "c", NOW.plus(Duration.ofMinutes(5))));
        assertTrue(guard.beforeWrite(USER, "c", NOW.plus(Duration.ofMinutes(5)).plusMillis(1)));
    }

    @Test
    @DisplayName("clock skew: a slow erasing-node clock cannot move the cut-off back — the database's write time counts")
    void databaseClockCounts() {
        // The erasing node's clock is 20 minutes behind the database's.
        store.databaseNow = NOW.plus(Duration.ofMinutes(20));
        guard.tombstoneUser(USER, List.of());
        assertFalse(guard.beforeWrite(USER, "c", NOW.plus(Duration.ofMinutes(19))), "captured before the tombstone was written");
        assertTrue(guard.beforeWrite(USER, "c", NOW.plus(Duration.ofMinutes(26))));
    }

    @Test
    @DisplayName("the drain barrier is bounded by one timeout in total, however many writers there are")
    void drainSharesOneDeadline() {
        List<Duration> asked = new ArrayList<>();
        AsyncErasureWriter slow = new FakeWriter() {
            @Override
            public boolean awaitDrained(Duration timeout) {
                asked.add(timeout);
                try {
                    Thread.sleep(Math.min(timeout.toMillis(), 300));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        };
        ErasureGuard two = new ErasureGuard(store, List.of(slow, slow), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                Duration.ofMillis(400), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        long started = System.nanoTime();
        assertEquals(2, two.drainLocal().size());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 700, "one deadline, not two");
        assertTrue(asked.get(1).compareTo(asked.get(0)) < 0, "the second writer gets what is left: " + asked);
    }

    @Test
    @DisplayName("the stored user key is a keyed hash, not the plain SHA-256 that travels on the cluster")
    void userKeyIsKeyed() {
        guard.tombstoneUser(USER, List.of());
        String hash = KvKeys.sha256(USER);
        assertFalse(store.hasTombstone(Kind.USER, hash));
        assertTrue(store.hasTombstone(Kind.USER, guard.userKey(hash)));
        assertNotEquals(hash, guard.userKey(hash));
    }

    @Nested
    @DisplayName("nothing is overlooked: pending removals of any writer, fair retries, a key that changed under a running node")
    class NothingOverlooked {

        @Test
        @DisplayName("a pending removal of the conversation by a writer no longer deployed fails the conversation delete")
        void conversationPendingOfAnUnknownWriter() {
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("retired", "r9", null, CONVERSATION, NOW));
            ErasureIncompleteException e = assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation(CONVERSATION));
            assertTrue(e.getMessage().contains("1 pending removal(s) remain"), e.getMessage());
            assertEquals(1, store.pendingCount(), "kept: no bean can remove it, so the row may still exist");
        }

        @Test
        @DisplayName("a pending removal of the user by a writer no longer deployed fails the last check, not the writer's own")
        void userPendingOfAnUnknownWriter() {
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("retired", "r8", guard.userKey(KvKeys.sha256(USER)), null, NOW));
            guard.verifyUserErased("fake", USER, () -> 0);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> guard.verifyNoPendingRemovals(USER));
            assertTrue(e.getMessage().contains("1 pending removal(s)"), e.getMessage());
        }

        @Test
        @DisplayName("the user's own pending removals of every writer are retried by the last check")
        void lastCheckRetries() {
            writer.rows.put("r1", CONVERSATION);
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("fake", "r1", guard.userKey(KvKeys.sha256(USER)), null, NOW));
            guard.verifyNoPendingRemovals(USER);
            assertEquals(0, store.pendingCount());
            assertEquals(List.of("r1"), writer.removed);
        }

        @Test
        @DisplayName("maintenance: a full batch of removals that keep failing does not starve a removable one behind it")
        void retriesAreFair() {
            for (int i = 0; i < ErasureGuard.RETRY_BATCH; i++) {
                store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("retired", "stuck-" + i, null, "c" + i, NOW.minusSeconds(600)));
            }
            writer.rows.put("later", CONVERSATION);
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("fake", "later", null, CONVERSATION, NOW.minusSeconds(1)));

            assertEquals(0, guard.retryDuePending(), "the first pass is full of removals that cannot succeed");
            assertEquals(1, guard.retryDuePending(), "they were moved back, so the next pass reaches the removable one");
            assertEquals(List.of("later"), writer.removed);
            assertEquals(ErasureGuard.RETRY_BATCH, store.pendingCount(), "the stuck ones stay pending");
        }

        @Test
        @DisplayName("the backlog counts what is stored, not what this process recorded")
        void backlog() {
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("fake", "r1", null, CONVERSATION, NOW));
            store.addPendingRemoval(new IErasureTombstoneStore.PendingRemoval("retired", "r2", null, CONVERSATION, NOW));
            assertEquals(2, guard.pendingRemovalBacklog());
        }

        private ErasureGuard nodeWith(byte[] vaultSubkey) {
            ErasureGuard node = new ErasureGuard(store, List.of(writer), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                    Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
            node.vaultKey = () -> vaultSubkey;
            return node;
        }

        @Test
        @DisplayName("a running node stops using its key as soon as the stored check value names another one")
        void runningNodeDropsAReplacedKey() {
            byte[] oldKey = new byte[32];
            byte[] newKey = new byte[32];
            newKey[0] = 1;
            ErasureGuard running = nodeWith(oldKey);
            String before = running.userKey("h");
            assertTrue(running.beforeWrite(USER, "c", NOW));

            // The operator adopts a new key: the check row is deleted, and a node with
            // the new key writes its own.
            store.removeMeta(ErasureGuard.KEY_CHECK_META);
            ErasureGuard restarted = nodeWith(newKey);
            assertNotEquals(before, restarted.userKey("h"));

            assertThrows(IllegalStateException.class, () -> running.userKey("h"), "the cached old key is not used any more");
            assertFalse(running.beforeWrite(USER, "c", NOW), "a guarded write on the stale node fails closed");
            assertThrows(IllegalStateException.class, () -> running.tombstoneUser("someone", List.of()));
        }

        @Test
        @DisplayName("re-checking the key is a plain read: it never writes a check value back")
        void recheckDoesNotReinsert() {
            ErasureGuard running = nodeWith(new byte[32]);
            running.userKey("h");
            store.replaceMeta(ErasureGuard.KEY_CHECK_META, "vault:someone-else");
            assertThrows(IllegalStateException.class, () -> running.userKey("h"));
            assertEquals(Optional.of("vault:someone-else"), store.meta(ErasureGuard.KEY_CHECK_META));
        }

        @Test
        @DisplayName("many conversations: one tombstone write and one drain for all, and only the one that failed is reported")
        void batch() {
            writer.rows.put("r1", "c1");
            writer.rows.put("r2", "c2");
            writer.rows.put("r3", "c3");
            writer.stuckConversation = "c2";

            Map<String, String> failed = guard.eraseConversations(List.of("c1", "c2", "c3"));

            assertEquals(List.of("c2"), List.copyOf(failed.keySet()));
            assertTrue(failed.get("c2").contains("1 row(s) remain"), failed.get("c2"));
            assertEquals(1, writer.drainCalls, "one barrier for the whole batch");
            assertEquals(Map.of("r2", "c2"), writer.rows);
            for (String id : List.of("c1", "c2", "c3")) {
                assertTrue(store.hasTombstone(Kind.CONVERSATION, id), id);
            }
        }

        @Test
        @DisplayName("many conversations: a writer that does not drain keeps every one of them, and none is touched")
        void batchUndrained() {
            writer.drains = false;
            writer.rows.put("r1", "c1");
            Map<String, String> failed = guard.eraseConversations(List.of("c1", "c2"));
            assertEquals(List.of("c1", "c2"), List.copyOf(failed.keySet()));
            assertEquals(0, writer.erasedConversations);
        }
    }

    @Test
    @DisplayName("drainReply says drained, or names the writer that did not drain")
    void drainReply() {
        assertEquals(Map.of("drained", true), guard.drainReply());
        writer.drains = false;
        Map<String, Object> reply = guard.drainReply();
        assertEquals(false, reply.get("drained"));
        assertTrue(String.valueOf(reply.get("error")).contains("fake"));
    }
}
