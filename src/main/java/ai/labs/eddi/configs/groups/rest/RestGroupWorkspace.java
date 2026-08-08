/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.runtime.internal.TeamCadenceService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * REST implementation for {@link IRestGroupWorkspace} (I13).
 * <p>
 * Reads run {@link TeamCadenceService#reconcile} first (read-repair): a cadence
 * discussion that finished since the last fire is settled before the workspace
 * is rendered, so a human never reads hour-old "running" state or stale
 * metrics.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestGroupWorkspace implements IRestGroupWorkspace {

    private static final Logger LOG = Logger.getLogger(RestGroupWorkspace.class);

    private final IGroupWorkspaceStore workspaceStore;
    private final IAgentGroupStore groupStore;
    private final IScheduleStore scheduleStore;
    private final TeamCadenceService teamCadenceService;
    private final SecurityIdentity identity;

    @Inject
    public RestGroupWorkspace(IGroupWorkspaceStore workspaceStore, IAgentGroupStore groupStore,
            IScheduleStore scheduleStore, TeamCadenceService teamCadenceService, SecurityIdentity identity) {
        this.workspaceStore = workspaceStore;
        this.groupStore = groupStore;
        this.scheduleStore = scheduleStore;
        this.teamCadenceService = teamCadenceService;
        this.identity = identity;
    }

    @Override
    public Response readWorkspace(String groupId) {
        try {
            requireGroupExists(groupId);
            GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
            teamCadenceService.reconcile(workspace);
            return Response.ok(workspace).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to read workspace for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response readBacklog(String groupId) {
        try {
            requireGroupExists(groupId);
            GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
            teamCadenceService.reconcile(workspace);
            return Response.ok(workspace.getBacklog().getTasks()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to read backlog for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response addBacklogTask(String groupId, BacklogTaskRequest request) {
        if (request == null || request.subject() == null || request.subject().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "subject is required")).build();
        }
        try {
            requireGroupExists(groupId);
            GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
            if (workspace.getBacklog().size() >= GroupWorkspace.MAX_BACKLOG_SIZE) {
                // Actionable, not silent: say WHAT is full and what to do about it.
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "The backlog already holds " + GroupWorkspace.MAX_BACKLOG_SIZE
                                + " tasks — complete or delete existing tasks before adding more"))
                        .build();
            }
            TaskItem task = workspace.getBacklog().addTask(new TaskItem(
                    request.subject().trim(),
                    request.description() != null ? request.description() : "",
                    request.priority()));
            workspaceStore.update(workspace);
            return Response.status(Response.Status.CREATED).entity(task).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to add backlog task for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response addCadence(String groupId, CadenceRequest request) {
        if (request == null || request.cronExpression() == null || request.cronExpression().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "cronExpression is required")).build();
        }
        try {
            requireGroupExists(groupId);
            ZoneId zone = request.timeZone() != null && !request.timeZone().isBlank()
                    ? ZoneId.of(request.timeZone())
                    : ZoneId.of("UTC");
            // Validate the cron by computing the first fire — an unparseable
            // expression must fail HERE, not silently never fire.
            Instant firstFire = CronParser.computeNextFire(request.cronExpression().trim(), Instant.now(), zone);

            GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
            String cadenceId = UUID.randomUUID().toString();
            String principal = identity != null && identity.getPrincipal() != null
                    && identity.getPrincipal().getName() != null && !identity.getPrincipal().getName().isBlank()
                            ? identity.getPrincipal().getName()
                            : TeamCadenceService.FALLBACK_USER_ID;

            var schedule = new ScheduleConfiguration();
            schedule.setName("team-cadence-" + groupId + "-" + cadenceId);
            schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
            schedule.setCronExpression(request.cronExpression().trim());
            schedule.setTimeZone(zone.getId());
            schedule.setEnabled(true);
            schedule.setUserId(principal);
            schedule.setCreatedBy(principal);
            schedule.setNextFire(firstFire);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(Map.of(
                    TeamCadenceService.METADATA_TYPE_KEY, TeamCadenceService.METADATA_TYPE_CADENCE,
                    TeamCadenceService.METADATA_GROUP_ID_KEY, groupId,
                    TeamCadenceService.METADATA_CADENCE_ID_KEY, cadenceId));
            String scheduleId = scheduleStore.createSchedule(schedule);

            var cadence = new Cadence(cadenceId, scheduleId, request.inputTemplate(),
                    request.maxBacklogTasksPerRun(), request.maxCostPerRun(), principal);
            workspace.addCadence(cadence);
            workspaceStore.update(workspace);
            LOG.infof("Cadence %s created for group %s (schedule %s, cron '%s')", cadenceId, sanitize(groupId),
                    scheduleId, sanitize(request.cronExpression()));
            return Response.status(Response.Status.CREATED).entity(cadence).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid cadence definition: " + e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to add cadence for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response deleteCadence(String groupId, String cadenceId) {
        try {
            requireGroupExists(groupId);
            GroupWorkspace workspace = workspaceStore.find(groupId);
            if (workspace == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "No workspace exists for this group")).build();
            }
            Cadence cadence = workspace.getCadences().stream()
                    .filter(c -> cadenceId.equals(c.cadenceId()))
                    .findFirst().orElse(null);
            if (cadence == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "No such cadence")).build();
            }
            // Schedule first: a cadence whose schedule outlives it would keep
            // firing into "cadence no longer exists" errors forever.
            try {
                scheduleStore.deleteSchedule(cadence.scheduleRef());
            } catch (Exception e) {
                LOG.warnf("Could not delete schedule %s for cadence %s: %s", cadence.scheduleRef(),
                        sanitize(cadenceId), sanitize(e.getMessage()));
            }
            workspace.removeCadence(cadenceId);
            workspaceStore.update(workspace);
            return Response.noContent().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete cadence %s for group %s", sanitize(cadenceId), sanitize(groupId));
            return Response.serverError().build();
        }
    }

    /** 404 for a workspace under a group id that names no stored group config. */
    private void requireGroupExists(String groupId) throws IResourceStore.ResourceNotFoundException,
            IResourceStore.ResourceStoreException {
        if (groupStore.getCurrentResourceId(groupId) == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found.");
        }
    }
}
