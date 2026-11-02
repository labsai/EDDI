/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fire-time authority of a schedule: whose teams count when the creator's
 * access is re-checked, and how {@code maxCostPerFire} reaches the fire's turn.
 */
class ScheduleFireExecutorAuthorityTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");

    private ScheduleFireExecutor executor;
    private UserDirectory directory;
    private ResourceAccessGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        executor = new ScheduleFireExecutor();
        directory = mock(UserDirectory.class);
        guard = mock(ResourceAccessGuard.class);
        when(directory.isActive()).thenReturn(true);
        set("userDirectory", directory);
        set("resourceAccessGuard", guard);
    }

    private void set(String name, Object value) throws Exception {
        var field = ScheduleFireExecutor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(executor, value);
    }

    private static ScheduleConfiguration schedule(List<String> teams) {
        var s = new ScheduleConfiguration();
        s.setId("sched-1");
        s.setAgentId("agent-1");
        s.setCreatedBy("alice");
        s.setCreatorTeams(teams);
        s.setCreatedAt(CREATED);
        return s;
    }

    private void lastSignIn(Instant at, String... teams) {
        var user = new DirectoryUser("alice", "sub-1", "alice", null, false, "Alice", List.of(teams), CREATED.minusSeconds(3600), at);
        when(directory.lookup(List.of("alice"))).thenReturn(Map.of("alice", user));
    }

    @Nested
    class CreatorTeams {

        @Test
        void aTeamTheCreatorLeftSinceNoLongerAuthorizesTheFire() {
            lastSignIn(CREATED.plusSeconds(86_400), "team:other");
            when(guard.checkPrincipalUse(eq("agent-1"), eq("alice"), anyCollection(), eq(false)))
                    .thenReturn(ResourceAccessGuard.UseCheck.DENIED);

            assertEquals(ResourceAccessGuard.UseCheck.DENIED, executor.creatorUseCheck(schedule(List.of("team:support"))));
            // The snapshot's team is gone from the creator's latest token: not counted.
            verify(guard).checkPrincipalUse("agent-1", "alice", List.of(), false);
        }

        @Test
        void aTeamStillListedAtTheLastSignInKeepsCounting() {
            lastSignIn(CREATED.plusSeconds(60), "team:support", "team:later");

            assertEquals(List.of("team:support"), executor.currentCreatorTeams(schedule(List.of("team:support"))));
        }

        @Test
        void aSignInOlderThanTheSnapshotSaysNothingNew() {
            // The directory entry predates the schedule: the snapshot is the fresher fact.
            lastSignIn(CREATED.minusSeconds(60));

            assertEquals(List.of("team:support"), executor.currentCreatorTeams(schedule(List.of("team:support"))));
        }

        @Test
        void noDirectoryEntryLeavesTheSnapshotStanding() {
            when(directory.lookup(List.of("alice"))).thenReturn(Map.of());

            assertEquals(List.of("team:support"), executor.currentCreatorTeams(schedule(List.of("team:support"))));
        }

        @Test
        void anInactiveDirectoryLeavesTheSnapshotStanding() {
            when(directory.isActive()).thenReturn(false);
            lastSignIn(CREATED.plusSeconds(60));

            assertEquals(List.of("team:support"), executor.currentCreatorTeams(schedule(List.of("team:support"))));
        }

        @Test
        void aDirectoryFailureLeavesTheSnapshotStanding() {
            when(directory.lookup(List.of("alice"))).thenThrow(new IllegalStateException("db down"));

            assertEquals(List.of("team:support"), executor.currentCreatorTeams(schedule(List.of("team:support"))));
        }
    }

    @Nested
    class MaxCostPerFire {

        private InputData input() {
            var input = new InputData();
            input.setContext(new HashMap<>());
            return input;
        }

        @Test
        void aFiniteAllowanceTravelsWithTheInput() {
            var s = schedule(List.of());
            s.setMaxCostPerFire(0.5);
            var input = input();

            ScheduleFireExecutor.addMaxCostPerFire(input, s);

            Context allowance = input.getContext().get(ScheduleConfiguration.CONTEXT_MAX_COST_PER_FIRE);
            assertNotNull(allowance, "maxCostPerFire must reach the turn");
            assertEquals(0.5, ((Number) allowance.getValue()).doubleValue(), 1e-9);
        }

        @Test
        void unlimitedAddsNothing() {
            var s = schedule(List.of());
            var input = input();

            s.setMaxCostPerFire(-1.0);
            ScheduleFireExecutor.addMaxCostPerFire(input, s);
            // Postgres stores 0 as NULL and reads it back as 0: unlimited, as before.
            s.setMaxCostPerFire(0.0);
            ScheduleFireExecutor.addMaxCostPerFire(input, s);
            s.setMaxCostPerFire(Double.POSITIVE_INFINITY);
            ScheduleFireExecutor.addMaxCostPerFire(input, s);

            assertFalse(input.getContext().containsKey(ScheduleConfiguration.CONTEXT_MAX_COST_PER_FIRE));
        }
    }
}
