/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.PendingHumanInput;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * C1 regression: the cluster recovery sweep runs every minute, and it used to
 * delete and re-create every finite HITL timeout schedule at
 * {@code max(pausedAt + timeout, now + 2 min)}. A deadline within two minutes
 * of a sweep was pushed out again on every pass, so AUTO_APPROVE, AUTO_REJECT
 * and ABORT never fired in a NATS cluster. These tests run several consecutive
 * sweeps over one schedule store and check that an armed schedule is never
 * touched, that it still fires at its deadline, and that a schedule lost in a
 * crash is still re-created — once.
 */
@DisplayName("HITL timeout re-arm across consecutive recovery sweeps (C1)")
class HitlTimeoutRearmSweepTest {

    private static final String CONV_ID = "conv-c1";
    private static final String GC_ID = "gc-c1";
    private static final String REGULAR_NAME = HitlSchedules.regularTimeoutScheduleName(CONV_ID);
    private static final String GROUP_NAME = HitlSchedules.groupTimeoutScheduleName(GC_ID);
    private static final int SWEEPS = 5;

    private IConversationMemoryStore memStore;
    private IGroupConversationStore gcStore;
    private IScheduleStore scheduleStore;
    private HitlCrashRecoveryObserver observer;

    /** The schedule store's rows, kept by the answers below. */
    private final List<ScheduleConfiguration> rows = new ArrayList<>();
    private int nextId;

    @BeforeEach
    void setUp() throws Exception {
        memStore = mock(IConversationMemoryStore.class);
        gcStore = mock(IGroupConversationStore.class);
        scheduleStore = mock(IScheduleStore.class);
        when(memStore.findConversationIdsByState(any())).thenReturn(List.of());
        when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of());
        when(gcStore.findByState(any())).thenReturn(List.of());
        when(gcStore.findByState(any(), any(), anyInt())).thenReturn(List.of());

        when(scheduleStore.createSchedule(any())).thenAnswer(invocation -> {
            ScheduleConfiguration schedule = invocation.getArgument(0);
            schedule.setId("schedule-" + (++nextId));
            rows.add(schedule);
            return schedule.getId();
        });
        when(scheduleStore.deleteSchedulesByName(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            int before = rows.size();
            rows.removeIf(row -> name.equals(row.getName()));
            return before - rows.size();
        });
        when(scheduleStore.readSchedulesByName(anyString(), anyInt())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            return rows.stream().filter(row -> name.equals(row.getName())).toList();
        });

        observer = new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, mock(IConversationService.class),
                mock(IGroupConversationService.class), true, true, Optional.empty());
    }

    // --- helpers -------------------------------------------------------------

    /** What the pause-time scheduler writes for a pause. */
    private ScheduleConfiguration armedRow(String name, String surface, String id, String policy, Instant pausedAt,
                                           Instant fireAt) {
        var schedule = new ScheduleConfiguration();
        schedule.setName(name);
        schedule.setEnabled(true);
        schedule.setOneTimeAt(fireAt.toString());
        schedule.setNextFire(fireAt);
        schedule.setCreatedAt(pausedAt);
        schedule.setMetadata(HitlSchedules.timeoutMetadata(policy, surface, id, HitlDecision.pauseIdOf(pausedAt)));
        schedule.setId("schedule-" + (++nextId));
        rows.add(schedule);
        return schedule;
    }

    /** A regular conversation paused at {@code pausedAt}, still waiting. */
    private void regularPause(String policy, String timeout, Instant pausedAt) throws Exception {
        var summary = new PendingApprovalSummary(CONV_ID, "agent-1", "user-1", pausedAt, "needs review", policy);
        summary.setApprovalTimeout(timeout);
        when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(summary));
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPausedAt(pausedAt);
        when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snapshot);
    }

    private GroupConversation groupApprovalPause(String policy, String timeout, Instant pausedAt) throws Exception {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAt(pausedAt);
        gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.valueOf(policy));
        gc.setHitlApprovalTimeout(timeout);
        when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));
        when(gcStore.read(GC_ID)).thenReturn(gc);
        return gc;
    }

    private void sweeps(int count) {
        for (int i = 0; i < count; i++) {
            observer.runClusterRecovery(id -> true);
        }
    }

    private List<ScheduleConfiguration> rowsNamed(String name) {
        return rows.stream().filter(row -> name.equals(row.getName())).toList();
    }

    /**
     * The poller at {@code now}: every enabled row due by then fires once and is
     * completed the way {@code markCompleted} leaves a one-shot.
     *
     * @return how many fires the timeout handler received
     */
    private int pollAt(Instant now) {
        int fired = 0;
        for (ScheduleConfiguration row : rows) {
            if (row.isEnabled() && row.getNextFire() != null && !row.getNextFire().isAfter(now)) {
                fired++;
                row.setEnabled(false);
                row.setNextFire(null);
                row.setLastFired(now);
            }
        }
        return fired;
    }

    // --- regular pauses --------------------------------------------------------

    @Nested
    @DisplayName("Regular pause")
    class Regular {

        @Test
        @DisplayName("a deadline 30 s away is not moved by five sweeps, and the poller fires it at the deadline")
        void armedScheduleSurvivesConsecutiveSweepsAndFires() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            Instant deadline = pausedAt.plus(Duration.ofSeconds(40));
            regularPause("AUTO_REJECT", "PT40S", pausedAt);
            ScheduleConfiguration armed = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT",
                    pausedAt, deadline);

            sweeps(SWEEPS);

            assertEquals(List.of(armed), rowsNamed(REGULAR_NAME), "the armed row must be the same row");
            assertEquals(deadline, armed.getNextFire(), "the deadline must not move");
            verify(scheduleStore, never()).deleteSchedulesByName(anyString());
            verify(scheduleStore, never()).createSchedule(any());
            assertEquals(1, pollAt(deadline), "the timeout handler must get its fire at the deadline");
        }

        @Test
        @DisplayName("an overdue row the poller has not reached yet stays due across sweeps")
        void overdueArmedScheduleStaysDue() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(90);
            Instant deadline = pausedAt.plus(Duration.ofSeconds(30));
            regularPause("ABORT", "PT30S", pausedAt);
            ScheduleConfiguration armed = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "ABORT",
                    pausedAt, deadline);

            sweeps(SWEEPS);

            assertEquals(deadline, armed.getNextFire());
            assertEquals(1, pollAt(Instant.now()), "an overdue armed row fires on the next poll");
        }

        @Test
        @DisplayName("a row waiting for its retry after a failed fire is kept, not reset")
        void failedRetryRowKept() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(120);
            regularPause("AUTO_APPROVE", "PT1M", pausedAt);
            ScheduleConfiguration retry = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE",
                    pausedAt, pausedAt.plusSeconds(60));
            retry.setFireStatus(ScheduleConfiguration.FireStatus.FAILED);
            retry.setFailCount(1);
            Instant retryAt = Instant.now().plusSeconds(90);
            retry.setNextRetryAt(retryAt);

            sweeps(SWEEPS);

            assertEquals(List.of(retry), rowsNamed(REGULAR_NAME));
            assertEquals(retryAt, retry.getNextRetryAt());
            assertEquals(1, retry.getFailCount());
        }

        @Test
        @DisplayName("a schedule lost in a crash is re-created once, at the original deadline, and then left alone")
        void missingScheduleRecreatedOnce() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            Instant deadline = pausedAt.plus(Duration.ofMinutes(5));
            regularPause("AUTO_APPROVE", "PT5M", pausedAt);

            sweeps(SWEEPS);

            verify(scheduleStore, times(1)).createSchedule(any());
            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertEquals(deadline, named.get(0).getNextFire());
            assertEquals(HitlDecision.pauseIdOf(pausedAt), named.get(0).getMetadata().get(HitlSchedules.METADATA_PAUSE_ID_KEY));
        }

        @Test
        @DisplayName("a lost schedule whose deadline already passed is re-created due now, not now + 2 min, and fires")
        void missingOverdueScheduleFiresPromptly() throws Exception {
            Instant pausedAt = Instant.now().minus(Duration.ofMinutes(10));
            regularPause("AUTO_REJECT", "PT30S", pausedAt);

            sweeps(SWEEPS);

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertFalse(named.get(0).getNextFire().isAfter(Instant.now()), "an overdue re-created timeout is due at once");
            assertEquals(1, pollAt(Instant.now()));
        }

        @Test
        @DisplayName("a timeout that fired and completed while the pause still waits is re-armed once, after the backoff")
        void spentRowRearmedAfterBackoff() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(60);
            regularPause("AUTO_REJECT", "PT30S", pausedAt);
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt, pausedAt.plusSeconds(30));
            Instant firedAt = Instant.now();
            assertEquals(1, pollAt(firedAt));

            sweeps(SWEEPS);

            verify(scheduleStore, times(1)).createSchedule(any());
            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertEquals(firedAt.plus(HitlCrashRecoveryObserver.SPENT_REARM_BACKOFF), named.get(0).getNextFire());
            assertEquals(0, pollAt(Instant.now()), "not again right away — no retry loop");
        }

        @Test
        @DisplayName("M1: a DEAD_LETTERED row of this pause can never fire — it is replaced")
        void deadLetteredRowReplaced() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(600);
            regularPause("AUTO_REJECT", "PT1M", pausedAt);
            ScheduleConfiguration dead = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt,
                    pausedAt.plusSeconds(60));
            dead.setFireStatus(ScheduleConfiguration.FireStatus.DEAD_LETTERED);
            dead.setFailCount(5);

            sweeps(SWEEPS);

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertNotSame(dead, named.get(0));
            assertEquals(1, pollAt(Instant.now()), "the replacement is due at once");
        }

        @Test
        @DisplayName("M1: a FAILED row out of retries (older build's ladder) is replaced")
        void failedAtMaxRetriesReplaced() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(600);
            regularPause("ABORT", "PT1M", pausedAt);
            ScheduleConfiguration exhausted = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "ABORT", pausedAt,
                    pausedAt.plusSeconds(60));
            exhausted.setFireStatus(ScheduleConfiguration.FireStatus.FAILED);
            exhausted.setFailCount(5);

            sweeps(SWEEPS);

            verify(scheduleStore, times(1)).createSchedule(any());
            assertNotSame(exhausted, rowsNamed(REGULAR_NAME).get(0));
        }

        @Test
        @DisplayName("M1: a row CLAIMED by another node (a fire in progress) is left untouched")
        void claimedRowUntouched() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(90);
            regularPause("AUTO_APPROVE", "PT1M", pausedAt);
            ScheduleConfiguration claimed = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", pausedAt,
                    pausedAt.plusSeconds(60));
            claimed.setFireStatus(ScheduleConfiguration.FireStatus.CLAIMED);
            claimed.setClaimedBy("node-2");
            claimed.setClaimedAt(Instant.now().minusSeconds(5));

            sweeps(SWEEPS);

            assertEquals(List.of(claimed), rowsNamed(REGULAR_NAME));
            verify(scheduleStore, never()).deleteSchedulesByName(anyString());
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("M1: a legacy row an undeploy switched off cannot fire — replaced by an enabled one")
        void undeployDisabledRowReplaced() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            ScheduleConfiguration undeployed = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT",
                    pausedAt, pausedAt.plus(Duration.ofMinutes(5)));
            undeployed.setEnabled(false);
            undeployed.setDisabledReason(ScheduleConfiguration.DISABLED_BY_UNDEPLOY);

            sweeps(SWEEPS);

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertTrue(named.get(0).isEnabled());
            assertEquals(pausedAt.plus(Duration.ofMinutes(5)), named.get(0).getNextFire(), "at the original deadline");
        }

        @Test
        @DisplayName("a row of this pause a person disabled is respected")
        void handDisabledRowLeftAlone() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            ScheduleConfiguration disabled = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt,
                    pausedAt.plus(Duration.ofMinutes(5)));
            disabled.setEnabled(false);

            sweeps(SWEEPS);

            assertEquals(List.of(disabled), rowsNamed(REGULAR_NAME));
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("S2: rows that cannot be read are replaced, not failed on every sweep")
        void unreadableRowsHealed() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt, pausedAt.plusSeconds(300));
            when(scheduleStore.readSchedulesByName(anyString(), anyInt()))
                    .thenThrow(new ResourceStoreException("malformed row"))
                    .thenAnswer(invocation -> rows.stream().filter(row -> REGULAR_NAME.equals(row.getName())).toList());

            sweeps(SWEEPS);

            verify(scheduleStore, times(1)).createSchedule(any());
            assertEquals(1, rowsNamed(REGULAR_NAME).size());
        }

        @Test
        @DisplayName("S2: two live rows of earlier pauses are both replaced by one for this pause")
        void twoStaleLiveRowsReplaced() throws Exception {
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", Instant.now().minus(Duration.ofHours(3)),
                    Instant.now().plus(Duration.ofHours(10)));
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", Instant.now().minus(Duration.ofHours(2)),
                    Instant.now().plus(Duration.ofHours(10)));
            Instant pausedAt = Instant.now().minusSeconds(5);
            regularPause("AUTO_REJECT", "PT1M", pausedAt);

            sweeps(SWEEPS);

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertEquals(HitlDecision.pauseIdOf(pausedAt), named.get(0).getMetadata().get(HitlSchedules.METADATA_PAUSE_ID_KEY));
        }

        @Test
        @DisplayName("S2: a spent row of this pause listed before a stale live row does not hide nor keep the stale one")
        void spentPlusStaleIsDeterministic() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(120);
            regularPause("AUTO_REJECT", "PT30S", pausedAt);
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt, pausedAt.plusSeconds(30));
            Instant firedAt = Instant.now().minusSeconds(60);
            pollAt(firedAt);
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", Instant.now().minus(Duration.ofHours(5)),
                    Instant.now().plus(Duration.ofHours(10)));

            sweeps(SWEEPS);

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size(), "the stale row of the earlier pause is gone");
            assertEquals(HitlDecision.pauseIdOf(pausedAt), named.get(0).getMetadata().get(HitlSchedules.METADATA_PAUSE_ID_KEY));
            assertEquals(firedAt.plus(HitlCrashRecoveryObserver.SPENT_REARM_BACKOFF), named.get(0).getNextFire());
        }

        @Test
        @DisplayName("S2: a legacy row (no pause id) that fired during this pause is spent — no fire on every sweep")
        void legacySpentRowNoLoop() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(600);
            regularPause("AUTO_REJECT", "PT1M", pausedAt);
            ScheduleConfiguration legacy = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt,
                    pausedAt.plusSeconds(60));
            legacy.getMetadata().remove(HitlSchedules.METADATA_PAUSE_ID_KEY);
            Instant firedAt = pausedAt.plusSeconds(61);
            pollAt(firedAt);

            sweeps(SWEEPS);

            verify(scheduleStore, times(1)).createSchedule(any());
            assertEquals(firedAt.plus(HitlCrashRecoveryObserver.SPENT_REARM_BACKOFF), rowsNamed(REGULAR_NAME).get(0).getNextFire());
            assertEquals(0, pollAt(Instant.now()));
        }

        @Test
        @DisplayName("the only live row belongs to an earlier pause: it is replaced by one for the current pause")
        void staleRowOfEarlierPauseReplaced() throws Exception {
            Instant earlierPause = Instant.now().minus(Duration.ofHours(1));
            ScheduleConfiguration stale = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE",
                    earlierPause, Instant.now().plus(Duration.ofHours(70)));
            Instant pausedAt = Instant.now().minusSeconds(5);
            regularPause("AUTO_REJECT", "PT1M", pausedAt);

            sweeps(SWEEPS);

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertNotSame(stale, named.get(0));
            assertEquals(pausedAt.plusSeconds(60), named.get(0).getNextFire());
            verify(scheduleStore, times(1)).createSchedule(any());
        }

        @Test
        @DisplayName("a row armed by an older build (no pause id) is kept")
        void legacyRowWithoutPauseIdKept() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT1M", pausedAt);
            ScheduleConfiguration legacy = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT",
                    pausedAt, pausedAt.plusSeconds(60));
            legacy.getMetadata().remove(HitlSchedules.METADATA_PAUSE_ID_KEY);

            sweeps(SWEEPS);

            assertEquals(List.of(legacy), rowsNamed(REGULAR_NAME));
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a pause restored after a lost resume replaces its spent row (startup pass)")
        void restoredPauseReplacesSpentRow() throws Exception {
            Instant pausedAt = Instant.now().minus(Duration.ofHours(1));
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", pausedAt, pausedAt.plusSeconds(60));
            pollAt(Instant.now()); // the timeout fired; its resume was lost with the node
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setAgentId("agent-1");
            snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPausedAt(pausedAt);
            snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            snapshot.setHitlApprovalTimeout("PT1M");
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS)).thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snapshot);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN))
                    .thenReturn(true);

            observer.runRecovery(Instant.now());

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertTrue(named.get(0).isEnabled(), "the restored pause must have a live timeout again");
        }
    }

    // --- group pauses --------------------------------------------------------

    @Nested
    @DisplayName("Group pauses")
    class Group {

        @Test
        @DisplayName("an armed group approval timeout 30 s away survives five sweeps and fires at the deadline")
        void armedGroupScheduleSurvivesSweeps() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            Instant deadline = pausedAt.plusSeconds(40);
            groupApprovalPause("AUTO_REJECT", "PT40S", pausedAt);
            ScheduleConfiguration armed = armedRow(GROUP_NAME, HitlSchedules.SURFACE_GROUP, GC_ID, "AUTO_REJECT", pausedAt,
                    deadline);

            sweeps(SWEEPS);

            assertEquals(List.of(armed), rowsNamed(GROUP_NAME));
            assertEquals(deadline, armed.getNextFire());
            verify(scheduleStore, never()).createSchedule(any());
            assertEquals(1, pollAt(deadline));
        }

        @Test
        @DisplayName("a lost group approval timeout is re-created once")
        void missingGroupScheduleRecreatedOnce() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            groupApprovalPause("ABORT", "PT5M", pausedAt);

            sweeps(SWEEPS);

            verify(scheduleStore, times(1)).createSchedule(any());
            assertEquals(pausedAt.plus(Duration.ofMinutes(5)), rowsNamed(GROUP_NAME).get(0).getNextFire());
        }

        @Test
        @DisplayName("an armed HUMAN member turn timeout survives five sweeps and fires at the deadline")
        void armedHumanTurnScheduleSurvivesSweeps() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            Instant deadline = pausedAt.plusSeconds(40);
            var gc = new GroupConversation();
            gc.setId(GC_ID);
            gc.setState(GroupConversationState.AWAITING_HUMAN_INPUT);
            gc.setPausedAt(pausedAt);
            gc.setHitlApprovalTimeout("PT40S");
            gc.setPendingHumanInput(new PendingHumanInput("human-1", "Human", 0, 0, 0, "OPINION", "Your turn", "SKIP_TURN",
                    pausedAt));
            when(gcStore.findByState(GroupConversationState.AWAITING_HUMAN_INPUT)).thenReturn(List.of(gc));
            when(gcStore.read(GC_ID)).thenReturn(gc);
            ScheduleConfiguration armed = armedRow(GROUP_NAME, HitlSchedules.SURFACE_GROUP_HUMAN, GC_ID, "SKIP_TURN", pausedAt,
                    deadline);

            sweeps(SWEEPS);

            assertEquals(List.of(armed), rowsNamed(GROUP_NAME));
            assertEquals(deadline, armed.getNextFire());
            verify(scheduleStore, never()).createSchedule(any());
            assertEquals(1, pollAt(deadline));
        }
    }
}
