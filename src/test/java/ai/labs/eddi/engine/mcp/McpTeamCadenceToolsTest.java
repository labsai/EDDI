/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IRestGroupLessons;
import ai.labs.eddi.configs.groups.IRestGroupLessons.ScheduleRequest;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace.CadenceRequest;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("McpTeamCadenceTools")
class McpTeamCadenceToolsTest {

    private static final String GROUP = "6aba70011a25f8be7920e0b1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private IRestGroupWorkspace workspace;
    private IRestGroupLessons lessons;
    private IScheduleStore scheduleStore;

    @BeforeEach
    void setUp() {
        workspace = mock(IRestGroupWorkspace.class);
        lessons = mock(IRestGroupLessons.class);
        scheduleStore = mock(IScheduleStore.class);
    }

    private McpTeamCadenceTools tools(Set<String> roles) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(identity.getRoles()).thenReturn(roles);
        return new McpTeamCadenceTools(workspace, lessons, scheduleStore, new JsonSerialization(mapper), identity, true);
    }

    @Test
    @DisplayName("a cadence needs a per-run cost cap over MCP, and the time zone reaches the workspace")
    void create() throws Exception {
        var t = tools(Set.of("eddi-editor"));
        assertEquals("BAD_REQUEST", mapper.readTree(t.createTeamCadence(GROUP, "0 9 * * 1", "Europe/Vienna", null, 3, null)).get("errorCode")
                .asText());
        assertEquals("BAD_REQUEST", mapper.readTree(t.createTeamCadence(GROUP, "0 9 * * 1", null, null, 3, 500.0)).get("errorCode").asText());
        verify(workspace, never()).addCadence(anyString(), any());

        when(workspace.addCadence(eq(GROUP), any())).thenReturn(Response.status(201).entity(Map.of("cadenceId", "c1")).build());
        assertEquals("c1", mapper.readTree(t.createTeamCadence(GROUP, " 0 9 * * 1 ", "Europe/Vienna", "Plan", 3, 2.0)).get("cadenceId").asText());
        ArgumentCaptor<CadenceRequest> request = ArgumentCaptor.forClass(CadenceRequest.class);
        verify(workspace).addCadence(eq(GROUP), request.capture());
        assertEquals(new CadenceRequest("0 9 * * 1", "Europe/Vienna", "Plan", 3, 2.0), request.getValue());
    }

    @Test
    @DisplayName("the list joins each cadence with its schedule's cron and time zone")
    void list() throws Exception {
        GroupWorkspace ws = new GroupWorkspace();
        ws.addCadence(new Cadence("c1", "s1", "Plan", 3, 2.0, "alice"));
        when(workspace.readWorkspace(GROUP)).thenReturn(Response.ok(ws).build());
        ScheduleConfiguration schedule = new ScheduleConfiguration();
        schedule.setCronExpression("0 9 * * 1");
        schedule.setTimeZone("Europe/Vienna");
        schedule.setEnabled(true);
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule);

        JsonNode row = mapper.readTree(tools(Set.of("eddi-admin")).listTeamCadences(GROUP)).get("cadences").get(0);
        assertEquals("c1", row.get("cadenceId").asText());
        assertEquals("Europe/Vienna", row.get("timeZone").asText());
        assertEquals("0 9 * * 1", row.get("cronExpression").asText());
    }

    @Test
    @DisplayName("lesson consolidation scheduling delegates with the time zone; viewers are refused")
    void consolidation() {
        when(lessons.schedule(eq(GROUP), any())).thenReturn(Response.ok(Map.of("scheduled", true)).build());
        tools(Set.of("eddi-editor")).scheduleLessonConsolidation(GROUP, "0 3 * * 1", "UTC");
        verify(lessons).schedule(GROUP, new ScheduleRequest("0 3 * * 1", "UTC"));

        assertThrows(ForbiddenException.class, () -> tools(Set.of("eddi-viewer")).createTeamCadence(GROUP, "0 9 * * 1", null, null, null, 1.0));
        assertThrows(ForbiddenException.class, () -> tools(Set.of("eddi-viewer")).unscheduleLessonConsolidation(GROUP));
    }
}
