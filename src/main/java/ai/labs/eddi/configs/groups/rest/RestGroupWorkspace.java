/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
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

    /** A workspace is a team's schedule, not a cron farm. */
    static final int MAX_CADENCES_PER_WORKSPACE = 20;
    /** The template renders into a discussion question — bound the prose. */
    static final int MAX_INPUT_TEMPLATE_LENGTH = 4000;
    /** Lost-CAS retries before telling the caller to try again. */
    public static final int MAX_CAS_ATTEMPTS = 3;

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
        // Final-review finding: the agent tool surface enforces these; a human
        // surface bypassing them let one request grow the persisted document
        // arbitrarily and made duplicate subjects ambiguous for the writeback's
        // subject-based outcome matching.
        if (request.subject().trim().length() > SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "subject exceeds " + SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH
                            + " characters"))
                    .build();
        }
        if (request.description() != null
                && request.description().length() > SharedTaskList.MAX_AGENT_TASK_DESCRIPTION_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "description exceeds " + SharedTaskList.MAX_AGENT_TASK_DESCRIPTION_LENGTH
                            + " characters"))
                    .build();
        }
        try {
            requireGroupExists(groupId);
            String subject = request.subject().trim();
            // Optimistic-concurrency retry (review finding): the cap and duplicate
            // checks run against THIS caller's snapshot; a plain whole-document
            // update let two concurrent adds both pass under the cap and the later
            // write drop the earlier task. A lost CAS re-reads and re-validates.
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
                if (workspace.getBacklog().size() >= GroupWorkspace.MAX_BACKLOG_SIZE) {
                    // Actionable, not silent: say WHAT is full and what to do about it.
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "The backlog already holds " + GroupWorkspace.MAX_BACKLOG_SIZE
                                    + " tasks — complete or delete existing tasks before adding more"))
                            .build();
                }
                if (workspace.getBacklog().getTasks().stream().anyMatch(t -> subject.equalsIgnoreCase(t.subject()))) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "A backlog task with that subject already exists — "
                                    + "writeback matches outcomes by subject, so subjects must be unique"))
                            .build();
                }
                TaskItem task = workspace.getBacklog().addTask(new TaskItem(
                        subject,
                        request.description() != null ? request.description() : "",
                        request.priority()));
                if (workspaceStore.casRevision(workspace)) {
                    return Response.status(Response.Status.CREATED).entity(task).build();
                }
            }
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "The workspace is being modified concurrently — retry the request"))
                    .build();
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
            if (workspace.getCadences().size() >= MAX_CADENCES_PER_WORKSPACE) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "This workspace already has " + MAX_CADENCES_PER_WORKSPACE
                                + " cadences — delete one before adding more"))
                        .build();
            }
            if (request.inputTemplate() != null && request.inputTemplate().length() > MAX_INPUT_TEMPLATE_LENGTH) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "inputTemplate exceeds " + MAX_INPUT_TEMPLATE_LENGTH + " characters"))
                        .build();
            }
            String indefinitePause = indefinitePauseWarning(groupId);
            if (indefinitePause != null) {
                LOG.warnf("Cadence added to group %s which pauses for approval under WAIT_INDEFINITELY: %s",
                        sanitize(groupId), indefinitePause);
            }
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
            try {
                workspaceStore.update(workspace);
            } catch (Exception e) {
                // Compensate (review finding): the schedule exists but no cadence
                // names its scheduleRef — it would fire into "cadence no longer
                // exists" forever and deleteCadence could never reach it.
                try {
                    scheduleStore.deleteSchedule(scheduleId);
                } catch (Exception cleanupFailure) {
                    LOG.errorf(cleanupFailure, "Orphaned schedule %s for group %s after a failed workspace write",
                            scheduleId, sanitize(groupId));
                }
                throw e;
            }
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

    /**
     * Warns when a group combines automated cadences with an approval gate that
     * never times out.
     * <p>
     * A cadence discussion that pauses for an approval nobody gives is not a
     * terminal state, so writeback never releases the workspace claim. The claim
     * TTL in {@code TeamCadenceService} reclaims it eventually, but "eventually,
     * after a day, by abandoning the run" is a backstop — the operator almost
     * certainly wanted either a finite {@code hitlConfig.timeoutPolicy} or no
     * approval gate on an unattended recurring run. Warn rather than reject: the
     * combination is legitimate for a team whose approver genuinely is always
     * available, and refusing it would break existing configs.
     *
     * @return a human-readable reason, or {@code null} when the combination is not
     *         present (or the config cannot be read — never fail an otherwise valid
     *         cadence over a diagnostic)
     */
    private String indefinitePauseWarning(String groupId) {
        try {
            var resourceId = groupStore.getCurrentResourceId(groupId);
            if (resourceId == null) {
                return null;
            }
            var config = groupStore.read(groupId, resourceId.getVersion());
            if (config == null || config.getPhases() == null) {
                return null;
            }
            boolean gatesForApproval = config.getPhases().stream().anyMatch(DiscussionPhase::requiresApproval);
            if (!gatesForApproval) {
                return null;
            }
            var hitl = config.getHitlConfig();
            boolean waitsForever = hitl == null || hitl.getTimeoutPolicy() == null
                    || hitl.getTimeoutPolicy() == HitlTimeoutPolicy.WAIT_INDEFINITELY;
            if (!waitsForever) {
                return null;
            }
            return "the group has requiresApproval phase(s) and hitlConfig.timeoutPolicy=WAIT_INDEFINITELY, so an "
                    + "unapproved run holds this team's cadence claim until the claim TTL reclaims it; set a finite "
                    + "timeoutPolicy to resolve such pauses properly";
        } catch (Exception e) {
            return null;
        }
    }
}
