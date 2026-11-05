/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestGroupLessons;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.groups.lessons.ILessonHistoryStore;
import ai.labs.eddi.engine.groups.lessons.LessonService;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.runtime.internal.LessonConsolidationService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestGroupLessons
 */
@ApplicationScoped
public class RestGroupLessons implements IRestGroupLessons {

    private static final Logger LOG = Logger.getLogger(RestGroupLessons.class);
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    /** Name prefix of a group's consolidation schedule; the group id follows. */
    public static final String SCHEDULE_NAME_PREFIX = "lesson-consolidation-";

    private final LessonService lessons;
    private final ILessonHistoryStore history;
    private final LessonConsolidationService consolidation;
    private final IScheduleStore scheduleStore;
    private final IAgentGroupStore groupStore;
    private final SecurityIdentity identity;
    private final ResourceAccessGuard accessGuard;

    /**
     * The minimum cron interval every schedule must keep (shared with
     * {@code RestScheduleStore}); field-injected so the constructor stays as it
     * was.
     */
    @ConfigProperty(name = "eddi.schedule.min-interval-seconds", defaultValue = "60")
    long minIntervalSeconds = 60;

    @Inject
    public RestGroupLessons(LessonService lessons, ILessonHistoryStore history, LessonConsolidationService consolidation,
            IScheduleStore scheduleStore,
            IAgentGroupStore groupStore, SecurityIdentity identity, ResourceAccessGuard accessGuard) {
        this.lessons = lessons;
        this.history = history;
        this.consolidation = consolidation;
        this.scheduleStore = scheduleStore;
        this.groupStore = groupStore;
        this.identity = identity;
        this.accessGuard = accessGuard;
    }

    @Override
    public Response list(String groupId, Integer limit) {
        try {
            requireGroupAccess(groupId, AccessLevel.VIEW);
            return Response.ok(lessons.viewsOf(groupId, clamp(limit))).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to list lessons for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response shared(String groupId) {
        try {
            requireGroupAccess(groupId, AccessLevel.VIEW);
            return Response.ok(lessons.sharedWith(groupId)).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to read shared lessons for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response history(String groupId, Integer limit) {
        try {
            requireGroupAccess(groupId, AccessLevel.VIEW);
            return Response.ok(history.listByGroup(groupId, clamp(limit))).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to read lesson history for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response consolidation(String groupId) {
        try {
            requireGroupAccess(groupId, AccessLevel.VIEW);
            LessonConfig config = lessons.configOf(groupId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("configured", config != null && config.consolidation() != null);
            out.put("hasModel", config != null && config.consolidation() != null && config.consolidation().hasModel());
            Optional<ScheduleConfiguration> schedule = scheduleStore.readScheduleByName(SCHEDULE_NAME_PREFIX + groupId);
            out.put("scheduled", schedule.isPresent());
            schedule.ifPresent(s -> {
                out.put("cronExpression", s.getCronExpression());
                out.put("timeZone", s.getTimeZone());
                out.put("enabled", s.isEnabled());
                out.put("nextFire", s.getNextFire());
            });
            return Response.ok(out).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to read the consolidation state of group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response schedule(String groupId, ScheduleRequest request) {
        if (request == null || request.cronExpression() == null || request.cronExpression().isBlank()) {
            return error(Response.Status.BAD_REQUEST, "cronExpression is required");
        }
        try {
            requireGroupAccess(groupId, AccessLevel.EDIT);
            LessonConfig config = lessons.configOf(groupId);
            if (config == null || config.consolidation() == null) {
                return error(Response.Status.CONFLICT,
                        "Lesson consolidation is not configured for this group. Add lessonConfig.consolidation to the group configuration first.");
            }
            ZoneId zone = request.timeZone() != null && !request.timeZone().isBlank() ? ZoneId.of(request.timeZone()) : ZoneId.of("UTC");
            String cron = request.cronExpression().trim();
            CronParser.validate(cron);
            // The same floor RestScheduleStore.validateSchedule applies to every schedule.
            long intervalSeconds = CronParser.computeMinIntervalSeconds(cron, zone);
            if (intervalSeconds < minIntervalSeconds) {
                return error(Response.Status.BAD_REQUEST, String.format("Invalid schedule: the cron interval (%ds) is below the minimum allowed "
                        + "(%ds, eddi.schedule.min-interval-seconds)", intervalSeconds, minIntervalSeconds));
            }
            Instant firstFire = CronParser.computeNextFire(cron, Instant.now(), zone);
            String name = SCHEDULE_NAME_PREFIX + groupId;
            scheduleStore.deleteSchedulesByName(name);

            String principal = principal();
            var schedule = new ScheduleConfiguration();
            schedule.setName(name);
            schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
            schedule.setCronExpression(cron);
            schedule.setTimeZone(zone.getId());
            schedule.setEnabled(true);
            schedule.setUserId(principal);
            schedule.setCreatedBy(principal);
            schedule.setNextFire(firstFire);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(Map.of(LessonConsolidationService.METADATA_TYPE_KEY, LessonConsolidationService.METADATA_TYPE_CONSOLIDATION,
                    LessonConsolidationService.METADATA_GROUP_ID_KEY, groupId));
            String id = scheduleStore.createSchedule(schedule);
            return Response.status(Response.Status.CREATED)
                    .entity(Map.of("scheduleId", id, "cronExpression", schedule.getCronExpression(), "timeZone",
                            zone.getId(), "nextFire", firstFire))
                    .build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException | DateTimeException e) {
            // IllegalStateException: a well-formed cron that never fires (0 0 30 2 *).
            return error(Response.Status.BAD_REQUEST, "Invalid schedule: " + e.getMessage());
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to schedule lesson consolidation for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response unschedule(String groupId) {
        try {
            requireGroupAccess(groupId, AccessLevel.EDIT);
            int removed = scheduleStore.deleteSchedulesByName(SCHEDULE_NAME_PREFIX + groupId);
            return removed > 0 ? Response.noContent().build() : error(Response.Status.NOT_FOUND, "No consolidation schedule exists for this group");
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to remove the lesson consolidation schedule of group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response run(String groupId, Boolean dryRun) {
        try {
            requireGroupAccess(groupId, AccessLevel.EDIT);
            var result = consolidation.run(groupId, Boolean.TRUE.equals(dryRun));
            return result.status() == LessonConsolidationService.Status.FAILED
                    ? Response.status(Response.Status.BAD_GATEWAY).entity(result).build()
                    : Response.ok(result).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to run lesson consolidation for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    private static int clamp(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private String principal() {
        return identity != null && identity.getPrincipal() != null && identity.getPrincipal().getName() != null
                && !identity.getPrincipal().getName().isBlank() ? identity.getPrincipal().getName() : "anonymous";
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

    private void requireGroupAccess(String groupId, AccessLevel required) throws IResourceStore.ResourceNotFoundException,
            IResourceStore.ResourceStoreException {
        if (groupStore.getCurrentResourceId(groupId) == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found.");
        }
        accessGuard.requireAccess(groupId, required, "group");
    }
}
