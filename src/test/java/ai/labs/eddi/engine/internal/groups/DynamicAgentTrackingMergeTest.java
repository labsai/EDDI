/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merging a member turn's dynamic-agent tracking into the group.
 * <p>
 * Each snapshot is <em>one member's</em> view and can be stale — member B's
 * turn may still name an agent member A tore down between the two. Merges also
 * run on coordinator threads, one per member turn, so the merge itself races.
 * Together those give the two properties pinned here: a teardown is final
 * regardless of the order snapshots arrive in, and concurrent merges cannot
 * duplicate an id (which would survive the single {@code remove} a later
 * teardown performs).
 *
 * @author ginccc
 */
@DisplayName("GroupLifecycleOps — dynamic-agent tracking merge")
class DynamicAgentTrackingMergeTest {

    private static SimpleConversationMemorySnapshot snapshotWith(Map<String, Object> stepData) {
        var snapshot = new SimpleConversationMemorySnapshot();
        var step = new SimpleConversationMemorySnapshot.SimpleConversationStep();
        var entries = new ArrayList<SimpleConversationMemorySnapshot.ConversationStepData>();
        stepData.forEach((key, value) -> entries.add(
                new SimpleConversationMemorySnapshot.ConversationStepData(key, value, null, null)));
        step.setConversationStep(entries);
        snapshot.setConversationSteps(new ArrayList<>(List.of(step)));
        return snapshot;
    }

    private static GroupConversation conversation() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        return gc;
    }

    @Test
    @DisplayName("a torn-down agent is dropped from the group's tracking")
    void teardownDropsTheAgent() {
        var gc = conversation();
        gc.getCreatedAgentIds().add("agent-1");

        GroupLifecycleOps.propagateDynamicAgentTracking(
                snapshotWith(Map.of(MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS, List.of("agent-1"))), gc);

        assertFalse(gc.getCreatedAgentIds().contains("agent-1"));
        assertTrue(gc.getTornDownAgentIds().contains("agent-1"), "the teardown must leave a tombstone");
    }

    @Test
    @DisplayName("a stale snapshot cannot resurrect a torn-down agent")
    void staleSnapshotCannotResurrect() {
        var gc = conversation();
        gc.getCreatedAgentIds().add("agent-1");

        // Member A tears it down...
        GroupLifecycleOps.propagateDynamicAgentTracking(
                snapshotWith(Map.of(MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS, List.of("agent-1"))), gc);

        // ...then member B's turn lands, still carrying a created list that names it.
        GroupLifecycleOps.propagateDynamicAgentTracking(
                snapshotWith(Map.of(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS, List.of("agent-1", "agent-2"))), gc);

        assertFalse(gc.getCreatedAgentIds().contains("agent-1"),
                "without a tombstone the id is re-added, keeps occupying a cap slot, and cleanup retries a deletion "
                        + "that already happened");
        assertTrue(gc.getCreatedAgentIds().contains("agent-2"), "the rest of the stale snapshot still merges");
    }

    @Test
    @DisplayName("teardown and creation in the same snapshot resolve to torn-down")
    void teardownWinsWithinOneSnapshot() {
        var gc = conversation();

        GroupLifecycleOps.propagateDynamicAgentTracking(snapshotWith(Map.of(
                MemoryKeys.DYNAMIC_CREATED_AGENT_IDS, List.of("agent-1"),
                MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS, List.of("agent-1"))), gc);

        assertFalse(gc.getCreatedAgentIds().contains("agent-1"),
                "step-data iteration order must not decide the outcome");
    }

    @Test
    @DisplayName("a torn-down agent is dropped from retained tracking too")
    void teardownDropsRetained() {
        var gc = conversation();
        gc.getRetainedAgentIds().add("agent-1");

        GroupLifecycleOps.propagateDynamicAgentTracking(snapshotWith(Map.of(
                MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS, List.of("agent-1"),
                MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS, List.of("agent-1"))), gc);

        assertFalse(gc.getRetainedAgentIds().contains("agent-1"));
    }

    @Test
    @DisplayName("concurrent merges of the same id do not duplicate it")
    void concurrentMergesDoNotDuplicate() throws Exception {
        var gc = conversation();
        int threads = 16;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    GroupLifecycleOps.propagateDynamicAgentTracking(
                            snapshotWith(Map.of(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS, List.of("agent-1"))), gc);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "merge deadlocked");

        assertEquals(1, gc.getCreatedAgentIds().size(),
                "CopyOnWriteArrayList makes each add atomic but not contains()-then-add(); a duplicate survives the "
                        + "single remove() a later teardown performs. Got: " + gc.getCreatedAgentIds());
    }

    @Test
    @DisplayName("recordTeardown reports whether it was the first to record")
    void recordTeardownIsIdempotent() {
        var gc = conversation();

        assertTrue(gc.recordTeardown("agent-1"));
        assertFalse(gc.recordTeardown("agent-1"), "a repeated teardown must not re-announce");
        assertFalse(gc.recordTeardown(null));
    }

    @Test
    @DisplayName("member display names survive a store reload as a concurrent map")
    void displayNamesStayConcurrentAfterReload() {
        var gc = conversation();
        // What deserialization does.
        gc.setMemberDisplayNames(Map.of("agent-1", "Alice"));

        gc.addMemberDisplayNameIfAbsent("agent-1", "agent-1");
        gc.addMemberDisplayNameIfAbsent("agent-2", "agent-2");

        assertEquals("Alice", gc.getMemberDisplayNames().get("agent-1"),
                "putIfAbsent must be atomic here, and must not overwrite an operator-chosen name");
        assertEquals("agent-2", gc.getMemberDisplayNames().get("agent-2"));
    }

    @Test
    @DisplayName("a teardown racing a merge still wins — the merge cannot re-add after it")
    void teardownRacingAMergeStillWins() throws Exception {
        // Ordering the writes inside recordTeardown is not enough on its own: a merge
        // can read the tombstone set, find the id absent, be descheduled while the
        // teardown records it, and then complete its own add. Only mutual exclusion
        // on a shared monitor closes that window, so this hammers the interleaving.
        for (int round = 0; round < 200; round++) {
            var gc = conversation();
            gc.getCreatedAgentIds().add("agent-1");
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);

            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    gc.recordTeardown("agent-1");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    GroupLifecycleOps.propagateDynamicAgentTracking(
                            snapshotWith(Map.of(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS, List.of("agent-1"))), gc);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "round " + round + " deadlocked");
            assertFalse(gc.getCreatedAgentIds().contains("agent-1"),
                    "round " + round + ": a recorded teardown must be final, whatever the interleaving");
        }
    }

    @Test
    @DisplayName("a retained-agent merge is guarded by the same monitor")
    void retainedMergeIsGuardedToo() throws Exception {
        for (int round = 0; round < 200; round++) {
            var gc = conversation();
            gc.getRetainedAgentIds().add("agent-1");
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);

            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    gc.recordTeardown("agent-1");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    GroupLifecycleOps.propagateDynamicAgentTracking(
                            snapshotWith(Map.of(MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS, List.of("agent-1"))), gc);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "round " + round + " deadlocked");
            assertFalse(gc.getRetainedAgentIds().contains("agent-1"), "round " + round);
        }
    }
}
