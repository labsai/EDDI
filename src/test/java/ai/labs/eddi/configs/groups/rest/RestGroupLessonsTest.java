/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestGroupLessons.ScheduleRequest;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConsolidationConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.groups.lessons.ILessonHistoryStore;
import ai.labs.eddi.engine.groups.lessons.LessonService;
import ai.labs.eddi.engine.runtime.internal.LessonConsolidationService;
import ai.labs.eddi.engine.runtime.internal.LessonConsolidationService.Status;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RestGroupLessons")
class RestGroupLessonsTest {

    private static final String GROUP = "group-1";

    private LessonService lessons;
    private ILessonHistoryStore history;
    private LessonConsolidationService consolidation;
    private IScheduleStore schedules;
    private IAgentGroupStore groups;
    private ResourceAccessGuard guard;
    private RestGroupLessons rest;

    private static final LessonConfig WITH_CONSOLIDATION = new LessonConfig(null, null, null,
            new LessonConsolidationConfig("anthropic", "claude-x", null, null, null, null, null, null, null, null));

    @BeforeEach
    void setUp() throws Exception {
        lessons = mock(LessonService.class);
        history = mock(ILessonHistoryStore.class);
        consolidation = mock(LessonConsolidationService.class);
        schedules = mock(IScheduleStore.class);
        groups = mock(IAgentGroupStore.class);
        guard = mock(ResourceAccessGuard.class);
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn("pm@example.com");
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(groups.getCurrentResourceId(GROUP)).thenReturn(mock(IResourceStore.IResourceId.class));
        lenient().when(lessons.configOf(GROUP)).thenReturn(WITH_CONSOLIDATION);
        rest = new RestGroupLessons(lessons, history, consolidation, schedules, groups, identity, guard);
    }

    @Test
    void readsNeedViewAndWritesNeedEdit() throws Exception {
        rest.list(GROUP, null);
        rest.shared(GROUP);
        rest.history(GROUP, null);
        rest.consolidation(GROUP);
        verify(guard, org.mockito.Mockito.times(4)).requireAccess(GROUP, AccessLevel.VIEW, "group");

        rest.schedule(GROUP, new ScheduleRequest("0 3 * * 1", null));
        rest.unschedule(GROUP);
        rest.run(GROUP, true);
        verify(guard, org.mockito.Mockito.times(3)).requireAccess(GROUP, AccessLevel.EDIT, "group");
    }

    @Test
    void anUnknownGroupIs404BeforeAnyWork() throws Exception {
        when(groups.getCurrentResourceId("missing")).thenReturn(null);
        assertEquals(404, rest.list("missing", null).getStatus());
        assertEquals(404, rest.run("missing", false).getStatus());
        verifyNoInteractions(lessons, consolidation, history);
    }

    @Test
    void forbiddenIsNotA500() throws Exception {
        doThrow(new ForbiddenException("denied")).when(guard).requireAccess(anyString(), any(), anyString());
        assertThrows(ForbiddenException.class, () -> rest.list(GROUP, null));
        assertThrows(ForbiddenException.class, () -> rest.shared(GROUP));
        assertThrows(ForbiddenException.class, () -> rest.history(GROUP, null));
        assertThrows(ForbiddenException.class, () -> rest.consolidation(GROUP));
        assertThrows(ForbiddenException.class, () -> rest.schedule(GROUP, new ScheduleRequest("0 3 * * 1", null)));
        assertThrows(ForbiddenException.class, () -> rest.unschedule(GROUP));
        assertThrows(ForbiddenException.class, () -> rest.run(GROUP, null));
    }

    @Test
    void theLimitIsClamped() {
        rest.list(GROUP, 99_999);
        verify(lessons).viewsOf(GROUP, RestGroupLessons.MAX_LIMIT);
        rest.history(GROUP, 0);
        verify(history).listByGroup(GROUP, 1);
        rest.history(GROUP, null);
        verify(history).listByGroup(GROUP, RestGroupLessons.DEFAULT_LIMIT);
    }

    // === scheduling ===

    @Test
    void schedulingCreatesACronScheduleCarryingTheMarkerAndTheGroup() throws Exception {
        when(schedules.createSchedule(any())).thenReturn("sched-1");

        var response = rest.schedule(GROUP, new ScheduleRequest("0 3 * * 1", "Europe/Vienna"));

        assertEquals(201, response.getStatus());
        var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(schedules).createSchedule(captor.capture());
        ScheduleConfiguration s = captor.getValue();
        assertEquals("lesson-consolidation-" + GROUP, s.getName());
        assertEquals("0 3 * * 1", s.getCronExpression());
        assertEquals("Europe/Vienna", s.getTimeZone());
        assertEquals("pm@example.com", s.getUserId());
        assertTrue(s.isEnabled());
        assertTrue(LessonConsolidationService.isLessonConsolidationSchedule(s.getMetadata()));
        assertEquals(GROUP, s.getMetadata().get(LessonConsolidationService.METADATA_GROUP_ID_KEY));
        assertTrue(s.getNextFire() != null);
    }

    @Test
    void reschedulingReplacesTheExistingSchedule() throws Exception {
        rest.schedule(GROUP, new ScheduleRequest("0 3 * * 1", null));
        verify(schedules).deleteSchedulesByName("lesson-consolidation-" + GROUP);
    }

    @Test
    void schedulingNeedsTheConsolidationConfigAndAValidCron() throws Exception {
        when(lessons.configOf(GROUP)).thenReturn(null);
        var response = rest.schedule(GROUP, new ScheduleRequest("0 3 * * 1", null));
        assertEquals(409, response.getStatus());
        assertTrue(response.getEntity().toString().contains("lessonConfig.consolidation"));

        when(lessons.configOf(GROUP)).thenReturn(WITH_CONSOLIDATION);
        assertEquals(400, rest.schedule(GROUP, new ScheduleRequest("0 0 3 * * ?", null)).getStatus(), "Quartz 6-field cron is refused");
        assertEquals(400, rest.schedule(GROUP, new ScheduleRequest(" ", null)).getStatus());
        assertEquals(400, rest.schedule(GROUP, null).getStatus());
        assertEquals(400, rest.schedule(GROUP, new ScheduleRequest("0 3 * * 1", "Mars/Olympus")).getStatus());
        verify(schedules, never()).createSchedule(any());
    }

    @Test
    void unschedulingIs404WhenThereIsNone() throws Exception {
        when(schedules.deleteSchedulesByName(anyString())).thenReturn(0, 1);
        assertEquals(404, rest.unschedule(GROUP).getStatus());
        assertEquals(204, rest.unschedule(GROUP).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStateSaysWhetherItIsConfiguredAndScheduled() throws Exception {
        when(schedules.readScheduleByName("lesson-consolidation-" + GROUP)).thenReturn(Optional.empty());
        Map<String, Object> none = (Map<String, Object>) rest.consolidation(GROUP).getEntity();
        assertEquals(true, none.get("configured"));
        assertEquals(true, none.get("hasModel"));
        assertEquals(false, none.get("scheduled"));

        var s = new ScheduleConfiguration();
        s.setCronExpression("0 3 * * 1");
        s.setTimeZone("UTC");
        s.setEnabled(true);
        when(schedules.readScheduleByName("lesson-consolidation-" + GROUP)).thenReturn(Optional.of(s));
        Map<String, Object> some = (Map<String, Object>) rest.consolidation(GROUP).getEntity();
        assertEquals(true, some.get("scheduled"));
        assertEquals("0 3 * * 1", some.get("cronExpression"));

        when(lessons.configOf(GROUP)).thenReturn(null);
        assertFalse((Boolean) ((Map<String, Object>) rest.consolidation(GROUP).getEntity()).get("configured"));
    }

    // === running now ===

    @Test
    void runPassesDryRunThroughAndMapsAFailureToBadGateway() {
        var ok = new LessonConsolidationService.Result(GROUP, "r", Status.COMPLETED, "ok", true, 8, 1, 0.01, List.of());
        when(consolidation.run(GROUP, true)).thenReturn(ok);
        assertEquals(200, rest.run(GROUP, true).getStatus());
        verify(consolidation).run(GROUP, true);

        when(consolidation.run(anyString(), anyBoolean()))
                .thenReturn(new LessonConsolidationService.Result(GROUP, "r", Status.FAILED, "no model", false, 0, 0, 0.0, List.of()));
        assertEquals(502, rest.run(GROUP, null).getStatus());
        verify(consolidation).run(GROUP, false);
    }
}
