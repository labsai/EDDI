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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;
import static ai.labs.eddi.engine.mcp.McpToolUtils.responseJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.toolFailure;

/**
 * MCP tools for a standing team's recurring work
 * ({@code docs/team-learning-loop.md}, {@code docs/workspaces.md}): cadences
 * that pull the backlog into a discussion on a cron, and the schedule of the
 * lesson-consolidation job.
 * <p>
 * The tools call the in-process {@link IRestGroupWorkspace} and
 * {@link IRestGroupLessons} beans, which check VIEW (reads) or EDIT (writes) on
 * the group for the MCP caller and record the caller as the cadence's owner.
 * Tier {@link McpRoles#AUTHOR}, equal to the REST {@code @RolesAllowed}.
 * <p>
 * <b>No approval step, deliberately.</b> A cadence or a consolidation run
 * spends only under ceilings that already exist: a cadence run is refused when
 * the team's monthly budget is spent ({@code TeamBudgetService.decide}) and
 * every model call it makes is charged against the tenant's monthly budget;
 * consolidation is capped by {@code lessonConfig.consolidation.maxCostPerRun}
 * (at most $5). Creating one raises no ceiling. Over MCP a cadence additionally
 * needs its own {@code maxCostPerRun}: a cadence a model books is never
 * unlimited per run.
 *
 * @since 6.8.0
 */
@ApplicationScoped
@McpErrorResults
public class McpTeamCadenceTools {

    private static final Logger LOGGER = Logger.getLogger(McpTeamCadenceTools.class);

    /** The most one cadence run may spend when an MCP client books it. */
    static final double MAX_COST_PER_RUN_USD = 100.0;
    static final int MAX_TASKS_PER_RUN = 20;

    private final IRestGroupWorkspace workspace;
    private final IRestGroupLessons lessons;
    private final IScheduleStore scheduleStore;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpTeamCadenceTools(IRestGroupWorkspace workspace, IRestGroupLessons lessons, IScheduleStore scheduleStore,
            IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.workspace = workspace;
        this.lessons = lessons;
        this.scheduleStore = scheduleStore;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "list_team_cadences", description = "List a standing team's cadences: each one's id, cron expression, time zone, next "
            + "fire, whether it is enabled, how many backlog tasks a run pulls, its per-run cost cap and who created it. Needs VIEW on "
            + "the group.")
    public String listTeamCadences(@ToolArg(description = "The group's id (required)") String groupId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank()) {
            return errorJson("groupId is required", "BAD_REQUEST", null);
        }
        try {
            Response read = workspace.readWorkspace(groupId.trim());
            if (read == null || read.getStatus() != 200 || !(read.getEntity() instanceof GroupWorkspace ws)) {
                return responseJson(jsonSerialization, read);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Cadence cadence : ws.getCadences()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cadenceId", cadence.cadenceId());
                row.put("inputTemplate", cadence.inputTemplate());
                row.put("maxBacklogTasksPerRun", cadence.maxBacklogTasksPerRun());
                row.put("maxCostPerRun", cadence.maxCostPerRun());
                row.put("createdBy", cadence.createdBy());
                ScheduleConfiguration schedule = schedule(cadence.scheduleRef());
                if (schedule != null) {
                    row.put("cronExpression", schedule.getCronExpression());
                    row.put("timeZone", schedule.getTimeZone());
                    row.put("enabled", schedule.isEnabled());
                    row.put("nextFire", schedule.getNextFire() != null ? schedule.getNextFire().toString() : null);
                    row.put("lastFired", schedule.getLastFired() != null ? schedule.getLastFired().toString() : null);
                } else {
                    row.put("schedule", "missing");
                }
                rows.add(row);
            }
            return jsonSerialization.serialize(Map.of("groupId", groupId.trim(), "cadences", rows));
        } catch (Exception e) {
            return toolFailure(LOGGER, "list_team_cadences", e);
        }
    }

    @Tool(name = "create_team_cadence", description = "Create a cadence for a standing team: on a 5-field cron (e.g. '0 9 * * 1' = "
            + "Mondays 09:00) in a time zone, a discussion pulls up to maxBacklogTasksPerRun backlog tasks. Every run spends against the "
            + "team's monthly budget (a spent budget skips the run) and the tenant's budget; maxCostPerRun is required here and caps "
            + "one run. At most 20 cadences per team. Needs EDIT on the group; the cadence runs as you.")
    public String createTeamCadence(@ToolArg(description = "The group's id (required)") String groupId,
                                    @ToolArg(description = "5-field cron expression (required)") String cronExpression,
                                    @ToolArg(description = "IANA time zone, e.g. Europe/Vienna (default UTC)", required = false) String timeZone,
                                    @ToolArg(description = "Opening question of each run (optional, at most 4000 characters)",
                                             required = false) String inputTemplate,
                                    @ToolArg(description = "Backlog tasks one run pulls, 1..20 (default 5)",
                                             required = false) Integer maxBacklogTasksPerRun,
                                    @ToolArg(description = "Cost cap of one run in USD, more than 0 and at most 100 (required)") Double maxCostPerRun) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank() || cronExpression == null || cronExpression.isBlank()) {
            return errorJson("groupId and cronExpression are required", "BAD_REQUEST", null);
        }
        if (maxCostPerRun == null || !Double.isFinite(maxCostPerRun) || maxCostPerRun <= 0 || maxCostPerRun > MAX_COST_PER_RUN_USD) {
            return errorJson("maxCostPerRun is required: more than 0 and at most " + (int) MAX_COST_PER_RUN_USD + " USD", "BAD_REQUEST", null);
        }
        if (maxBacklogTasksPerRun != null && (maxBacklogTasksPerRun < 1 || maxBacklogTasksPerRun > MAX_TASKS_PER_RUN)) {
            return errorJson("maxBacklogTasksPerRun must be between 1 and " + MAX_TASKS_PER_RUN, "BAD_REQUEST", null);
        }
        try {
            int tasks = maxBacklogTasksPerRun == null ? 0 : maxBacklogTasksPerRun;
            return responseJson(jsonSerialization, workspace.addCadence(groupId.trim(),
                    new CadenceRequest(cronExpression.trim(), blankToNull(timeZone), inputTemplate, tasks, maxCostPerRun)));
        } catch (Exception e) {
            return toolFailure(LOGGER, "create_team_cadence", e);
        }
    }

    @Tool(name = "delete_team_cadence", description = "Delete one of a standing team's cadences and its schedule. Needs EDIT on the group.")
    public String deleteTeamCadence(@ToolArg(description = "The group's id (required)") String groupId,
                                    @ToolArg(description = "The cadenceId, from list_team_cadences (required)") String cadenceId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank() || cadenceId == null || cadenceId.isBlank()) {
            return errorJson("groupId and cadenceId are required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization, workspace.deleteCadence(groupId.trim(), cadenceId.trim()));
        } catch (Exception e) {
            return toolFailure(LOGGER, "delete_team_cadence", e);
        }
    }

    @Tool(name = "read_lesson_consolidation", description = "Whether a standing team's lesson consolidation is configured "
            + "(lessonConfig.consolidation on the group) and scheduled, with its cron and time zone. Needs VIEW on the group.")
    public String readLessonConsolidation(@ToolArg(description = "The group's id (required)") String groupId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank()) {
            return errorJson("groupId is required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization, lessons.consolidation(groupId.trim()));
        } catch (Exception e) {
            return toolFailure(LOGGER, "read_lesson_consolidation", e);
        }
    }

    @Tool(name = "schedule_lesson_consolidation", description = "Schedule (or reschedule) a standing team's lesson-consolidation job on a "
            + "5-field cron in a time zone. The group must carry lessonConfig.consolidation; each run is capped by its maxCostPerRun "
            + "(at most $5). Needs EDIT on the group.")
    public String scheduleLessonConsolidation(@ToolArg(description = "The group's id (required)") String groupId,
                                              @ToolArg(description = "5-field cron expression, e.g. '0 3 * * 1' (required)") String cronExpression,
                                              @ToolArg(description = "IANA time zone (default UTC)", required = false) String timeZone) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank() || cronExpression == null || cronExpression.isBlank()) {
            return errorJson("groupId and cronExpression are required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization,
                    lessons.schedule(groupId.trim(), new ScheduleRequest(cronExpression.trim(), blankToNull(timeZone))));
        } catch (Exception e) {
            return toolFailure(LOGGER, "schedule_lesson_consolidation", e);
        }
    }

    @Tool(name = "unschedule_lesson_consolidation", description = "Remove a standing team's lesson-consolidation schedule. Needs EDIT on "
            + "the group.")
    public String unscheduleLessonConsolidation(@ToolArg(description = "The group's id (required)") String groupId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank()) {
            return errorJson("groupId is required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization, lessons.unschedule(groupId.trim()));
        } catch (Exception e) {
            return toolFailure(LOGGER, "unschedule_lesson_consolidation", e);
        }
    }

    /**
     * The cadence's schedule, null only when there is none. A store failure is
     * thrown: reported as "missing" it would read as a cadence that never fires.
     */
    private ScheduleConfiguration schedule(String scheduleId) throws IResourceStore.ResourceStoreException {
        if (scheduleId == null) {
            return null;
        }
        try {
            return scheduleStore.readSchedule(scheduleId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
