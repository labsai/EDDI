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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        // Like both stores: the rows of the name, at most `limit` of them.
        when(scheduleStore.readSchedulesByName(anyString(), anyInt())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return rows.stream().filter(row -> name.equals(row.getName())).limit(limit).toList();
        });
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            rows.removeIf(row -> id.equals(row.getId()));
            return null;
        }).when(scheduleStore).deleteSchedule(anyString());

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
            verify(scheduleStore, never()).deleteSchedule(anyString());
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
            var meters = new SimpleMeterRegistry();
            observer.meterRegistry = meters;

            sweeps(SWEEPS);

            assertEquals(1.0, meters.counter(HitlCrashRecoveryObserver.METRIC_REARMED_SPENT, "surface", "regular").count(),
                    "the spent re-arm is counted once");
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
            verify(scheduleStore, never()).deleteSchedule(anyString());
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
        @DisplayName("a failed read writes nothing: armed and hand-disabled rows stay as they are, the next sweep reads again")
        void failedReadLeavesRowsAlone() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            ScheduleConfiguration armed = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt,
                    pausedAt.plusSeconds(300));
            when(scheduleStore.readSchedulesByName(anyString(), anyInt())).thenThrow(new ResourceStoreException("transient"));

            sweeps(SWEEPS);

            assertEquals(List.of(armed), rowsNamed(REGULAR_NAME));
            verify(scheduleStore, never()).deleteSchedule(anyString());
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a result that hit its bound creates nothing: the stale rows read are removed, the protected row beyond is kept")
        void truncatedResultDrainsStaleRowsAndKeepsProtectedOne() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            for (int i = 0; i < 100; i++) {
                armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", Instant.now().minus(Duration.ofHours(2 + i)),
                        Instant.now().plus(Duration.ofHours(10)));
            }
            // This pause's own row, disabled by a person, beyond the first page of the
            // read.
            ScheduleConfiguration disabled = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt,
                    pausedAt.plus(Duration.ofMinutes(5)));
            disabled.setEnabled(false);

            sweeps(1);

            assertEquals(List.of(disabled), rowsNamed(REGULAR_NAME), "the 100 stale rows read are removed by id");
            verify(scheduleStore, never()).createSchedule(any());

            sweeps(SWEEPS);

            assertEquals(List.of(disabled), rowsNamed(REGULAR_NAME), "the next pass sees the disabled row and respects it");
            assertFalse(disabled.isEnabled());
            verify(scheduleStore, never()).createSchedule(any());
            verify(scheduleStore, never()).deleteSchedulesByName(anyString());
        }

        @Test
        @DisplayName("a row that is not a HITL timeout under the reserved name does not pass for the pause's timeout")
        void foreignRowDoesNotCountAsArmed() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            var foreign = new ScheduleConfiguration();
            foreign.setName(REGULAR_NAME);
            foreign.setEnabled(true);
            foreign.setNextFire(Instant.now().plus(Duration.ofDays(30)));
            foreign.setId("schedule-foreign");
            rows.add(foreign);

            sweeps(1);

            verify(scheduleStore, times(1)).createSchedule(any());
            assertTrue(rowsNamed(REGULAR_NAME).contains(foreign), "a row that is not a HITL timeout is not the sweep's to delete");
        }

        @Test
        @DisplayName("a newer pause's timeout read by a sweep of the earlier pause is neither deleted nor joined by a stale row")
        void newerPausesRowReadBeforeTheCheckSurvives() throws Exception {
            // The scan saw pause A; before the read, A was resumed and pause B committed
            // and armed its own row. Judged for A, B's row is "an earlier pause's" — only
            // the re-check before any write tells them apart.
            Instant pausedAt = Instant.now().minusSeconds(10);
            Instant newerPause = Instant.now().minusSeconds(1);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            ScheduleConfiguration newer = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", newerPause,
                    newerPause.plusSeconds(300));
            var current = new ConversationMemorySnapshot();
            current.setConversationState(ConversationState.AWAITING_HUMAN);
            current.setHitlPausedAt(newerPause);
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(current);

            sweeps(1);

            assertEquals(List.of(newer), rowsNamed(REGULAR_NAME), "the newer pause's own timeout must survive");
            verify(scheduleStore, never()).deleteSchedule(anyString());
            verify(scheduleStore, never()).createSchedule(any());
            verify(scheduleStore, never()).deleteSchedulesByName(anyString());
        }

        @Test
        @DisplayName("the withdrawal after a vanished pause removes only the row this sweep created, not a newer pause's timeout")
        void withdrawalKeepsNewerPausesRow() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            Instant newerPause = Instant.now().minusSeconds(1);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            var stillA = new ConversationMemorySnapshot();
            stillA.setConversationState(ConversationState.AWAITING_HUMAN);
            stillA.setHitlPausedAt(pausedAt);
            var gone = new ConversationMemorySnapshot();
            gone.setConversationState(ConversationState.AWAITING_HUMAN);
            gone.setHitlPausedAt(newerPause); // the scanned pause was resumed; a new one began
            // First check (before writing): still pause A. Between the create and the
            // second check, A is resumed and B pauses and arms its own row.
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(stillA).thenAnswer(invocation -> {
                armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", newerPause, newerPause.plusSeconds(300));
                return gone;
            });

            sweeps(1);

            verify(scheduleStore, times(1)).createSchedule(any());
            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size(), "the row this sweep created is withdrawn; the newer pause's own timeout survives");
            assertEquals(HitlDecision.pauseIdOf(newerPause), named.get(0).getMetadata().get(HitlSchedules.METADATA_PAUSE_ID_KEY));
        }

        @Test
        @DisplayName("a withdrawal that fails leaves a row bound to the ended pause — logged, the sweep goes on")
        void failedWithdrawalIsNotFatal() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(10);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            var stillA = new ConversationMemorySnapshot();
            stillA.setConversationState(ConversationState.AWAITING_HUMAN);
            stillA.setHitlPausedAt(pausedAt);
            var ended = new ConversationMemorySnapshot();
            ended.setConversationState(ConversationState.READY);
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(stillA, ended);
            doThrow(new ResourceStoreException("down")).when(scheduleStore).deleteSchedule(anyString());

            assertDoesNotThrow(() -> sweeps(1));

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertEquals(HitlDecision.pauseIdOf(pausedAt), named.get(0).getMetadata().get(HitlSchedules.METADATA_PAUSE_ID_KEY),
                    "the leftover is bound to the ended pause, so its fire is refused");
        }

        @Test
        @DisplayName("a legacy row (no pause id) a person disabled during this pause stays disabled")
        void legacyHandDisabledRowOfThisPauseKept() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(600);
            regularPause("AUTO_APPROVE", "PT1H", pausedAt);
            ScheduleConfiguration legacy = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", pausedAt,
                    pausedAt.plus(Duration.ofHours(1)));
            legacy.getMetadata().remove(HitlSchedules.METADATA_PAUSE_ID_KEY);
            legacy.setCreatedAt(pausedAt.plusMillis(5)); // armed right after the pause began
            legacy.setEnabled(false); // switched off by hand; it keeps its next fire

            sweeps(SWEEPS);

            assertEquals(List.of(legacy), rowsNamed(REGULAR_NAME));
            assertFalse(legacy.isEnabled());
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a disabled legacy row created before this pause began belongs to an earlier pause — replaced")
        void legacyDisabledRowOfEarlierPauseReplaced() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(60);
            regularPause("AUTO_REJECT", "PT5M", pausedAt);
            ScheduleConfiguration legacy = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE",
                    pausedAt.minus(Duration.ofHours(3)), pausedAt.minus(Duration.ofHours(2)));
            legacy.getMetadata().remove(HitlSchedules.METADATA_PAUSE_ID_KEY);
            legacy.setEnabled(false);

            sweeps(SWEEPS);

            List<ScheduleConfiguration> named = rowsNamed(REGULAR_NAME);
            assertEquals(1, named.size());
            assertNotSame(legacy, named.get(0));
            assertEquals(HitlDecision.pauseIdOf(pausedAt), named.get(0).getMetadata().get(HitlSchedules.METADATA_PAUSE_ID_KEY));
        }

        @Test
        @DisplayName("the one-hour backoff counts only completed rows of this pause, not an earlier pause's later fire")
        void backoffIgnoresEarlierPausesFire() throws Exception {
            Instant pausedAt = Instant.now().minusSeconds(600);
            regularPause("AUTO_REJECT", "PT30S", pausedAt);
            armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT", pausedAt, pausedAt.plusSeconds(30));
            Instant ownFire = Instant.now().minusSeconds(500);
            pollAt(ownFire);
            ScheduleConfiguration earlier = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_REJECT",
                    pausedAt.minus(Duration.ofHours(1)), pausedAt.minusSeconds(3000));
            earlier.setEnabled(false);
            earlier.setNextFire(null);
            earlier.setLastFired(Instant.now().minusSeconds(100)); // later than this pause's own fire

            sweeps(1);

            assertEquals(ownFire.plus(HitlCrashRecoveryObserver.SPENT_REARM_BACKOFF), rowsNamed(REGULAR_NAME).get(0).getNextFire());
        }

        @Test
        @DisplayName("a pause restored after a lost resume keeps a row of its own that a person disabled")
        void restoredPauseKeepsHandDisabledRow() throws Exception {
            Instant pausedAt = Instant.now().minus(Duration.ofHours(1));
            ScheduleConfiguration disabled = armedRow(REGULAR_NAME, HitlSchedules.SURFACE_REGULAR, CONV_ID, "AUTO_APPROVE", pausedAt,
                    pausedAt.plusSeconds(60));
            disabled.setEnabled(false);
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

            assertEquals(List.of(disabled), rowsNamed(REGULAR_NAME));
            assertFalse(disabled.isEnabled());
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
