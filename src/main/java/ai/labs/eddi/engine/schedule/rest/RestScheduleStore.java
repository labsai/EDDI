/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.internal.CronDescriber;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * JAX-RS implementation of {@link IRestScheduleStore}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestScheduleStore implements IRestScheduleStore {

    private static final Logger LOGGER = Logger.getLogger(RestScheduleStore.class);

    @Inject
    IScheduleStore scheduleStore;

    @Inject
    ScheduleFireExecutor fireExecutor;

    @Inject
    SchedulePollerService pollerService;

    @ConfigProperty(name = "eddi.schedule.default-timezone", defaultValue = "UTC")
    String defaultTimeZone;

    @ConfigProperty(name = "eddi.schedule.min-interval-seconds", defaultValue = "60")
    long minIntervalSeconds;

    @Override
    public List<ScheduleConfiguration> readAllSchedules(String agentId) {
        try {
            List<ScheduleConfiguration> schedules;
            if (agentId != null && !agentId.isBlank()) {
                schedules = scheduleStore.readSchedulesByAgentId(agentId);
            } else {
                schedules = scheduleStore.readAllSchedules(500); // Fix #12
            }
            // Enrich with cron descriptions
            schedules.forEach(this::enrichCronDescription);
            return schedules;
        } catch (Exception e) {
            LOGGER.error("Failed to read schedules", e);
            throw new InternalServerErrorException("Failed to read schedules");
        }
    }

    @Override
    public ScheduleConfiguration readSchedule(String scheduleId) {
        try {
            ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
            enrichCronDescription(schedule);
            return schedule;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to read schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to read schedule");
        }
    }

    @Override
    public Response createSchedule(ScheduleConfiguration schedule) {
        try {
            // Validate
            validateSchedule(schedule);

            // Apply trigger-type-aware defaults
            applyDefaults(schedule);

            // Compute initial nextFire
            computeInitialNextFire(schedule);

            String id = scheduleStore.createSchedule(schedule);
            schedule.setId(id);
            enrichCronDescription(schedule);

            return Response.created(URI.create("/schedulestore/schedules/" + id)).entity(schedule).build();
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid schedule configuration: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid schedule configuration").build();
        } catch (Exception e) {
            LOGGER.error("Failed to create schedule", e);
            throw new InternalServerErrorException("Failed to create schedule");
        }
    }

    @Override
    public Response updateSchedule(String scheduleId, ScheduleConfiguration schedule) {
        try {
            validateSchedule(schedule);

            // Recompute nextFire
            computeInitialNextFire(schedule);

            scheduleStore.updateSchedule(scheduleId, schedule);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid schedule update for " + scheduleId + ": " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid schedule configuration").build();
        } catch (Exception e) {
            LOGGER.error("Failed to update schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to update schedule");
        }
    }

    @Override
    public Response deleteSchedule(String scheduleId) {
        try {
            scheduleStore.deleteSchedule(scheduleId);
            return Response.noContent().build();
        } catch (Exception e) {
            LOGGER.error("Failed to delete schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to delete schedule");
        }
    }

    @Override
    public Response enableSchedule(String scheduleId) {
        return setEnabled(scheduleId, true);
    }

    @Override
    public Response disableSchedule(String scheduleId) {
        return setEnabled(scheduleId, false);
    }

    @Override
    public Response fireNow(String scheduleId) {
        try {
            ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
            ScheduleFireLog fireLog = fireExecutor.fire(schedule, pollerService.getInstanceId(), 1);
            return Response.ok(fireLog).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to manually fire schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to fire schedule");
        }
    }

    @Override
    public List<ScheduleFireLog> readFireLogs(String scheduleId, int limit) {
        try {
            return scheduleStore.readFireLogs(scheduleId, limit);
        } catch (Exception e) {
            LOGGER.error("Failed to read fire logs for schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to read fire logs");
        }
    }

    @Override
    public List<ScheduleFireLog> readFailedFires(int limit) {
        try {
            return scheduleStore.readFailedFireLogs(limit);
        } catch (Exception e) {
            LOGGER.error("Failed to read failed fires", e);
            throw new InternalServerErrorException("Failed to read failed fires");
        }
    }

    @Override
    public Response retryDeadLetter(String scheduleId) {
        try {
            scheduleStore.requeueDeadLetter(scheduleId);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found or not dead-lettered: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to retry dead-letter " + scheduleId, e);
            throw new InternalServerErrorException("Failed to retry dead-letter");
        }
    }

    // Fix #8: dismissDeadLetter uses markCompleted with proper nextFire recompute
    @Override
    public Response dismissDeadLetter(String scheduleId) {
        try {
            ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
            Instant nextFire = computeNextFireForSchedule(schedule);
            scheduleStore.markCompleted(scheduleId, nextFire);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to dismiss dead-letter " + scheduleId, e);
            throw new InternalServerErrorException("Failed to dismiss dead-letter");
        }
    }

    // --- Helpers ---

    // Fix #3: Atomic enable/disable instead of read-then-write race
    private Response setEnabled(String scheduleId, boolean enabled) {
        try {
            Instant nextFire = null;
            if (enabled) {
                // Need to read schedule to compute nextFire
                ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
                nextFire = computeNextFireForSchedule(schedule);
            }
            scheduleStore.setScheduleEnabled(scheduleId, enabled, nextFire);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to set enabled=" + enabled + " for schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to update schedule");
        }
    }

    private void applyDefaults(ScheduleConfiguration schedule) {
        // Infer trigger type from fields — must also handle the case where the
        // default CRON value is set but heartbeatIntervalSeconds indicates HEARTBEAT
        if (schedule.getTriggerType() == null || schedule.getHeartbeatIntervalSeconds() != null) {
            if (schedule.getHeartbeatIntervalSeconds() != null) {
                schedule.setTriggerType(TriggerType.HEARTBEAT);
            } else {
                schedule.setTriggerType(TriggerType.CRON);
            }
        }

        if (schedule.getEnvironment() == null || schedule.getEnvironment().isBlank()) {
            schedule.setEnvironment("production");
        }
        if (schedule.getUserId() == null || schedule.getUserId().isBlank()) {
            schedule.setUserId("system:scheduler");
        }
        if (schedule.getConversationStrategy() == null || schedule.getConversationStrategy().isBlank()) {
            // Heartbeats default to persistent, cron to new
            schedule.setConversationStrategy(schedule.getTriggerType() == TriggerType.HEARTBEAT ? "persistent" : "new");
        }
        if (schedule.getTimeZone() == null || schedule.getTimeZone().isBlank()) {
            schedule.setTimeZone(defaultTimeZone);
        }
        if (schedule.getFireStatus() == null) {
            schedule.setFireStatus(FireStatus.PENDING);
        }
        // Heartbeat: default message if unset
        if (schedule.getTriggerType() == TriggerType.HEARTBEAT && (schedule.getMessage() == null || schedule.getMessage().isBlank())) {
            schedule.setMessage("heartbeat");
        }
    }

    private void computeInitialNextFire(ScheduleConfiguration schedule) {
        if (schedule.getTriggerType() == TriggerType.HEARTBEAT && schedule.getHeartbeatIntervalSeconds() != null) {
            // Heartbeat: first fire = now + interval
            schedule.setNextFire(Instant.now().plusSeconds(schedule.getHeartbeatIntervalSeconds()));
        } else if (schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank()) {
            ZoneId zoneId = ZoneId.of(schedule.getTimeZone());
            Instant nextFire = CronParser.computeNextFire(schedule.getCronExpression(), Instant.now(), zoneId);
            schedule.setNextFire(nextFire);
        } else if (schedule.getOneTimeAt() != null && !schedule.getOneTimeAt().isBlank()) {
            schedule.setNextFire(Instant.parse(schedule.getOneTimeAt()));
        }
    }

    private Instant computeNextFireForSchedule(ScheduleConfiguration schedule) {
        if (schedule.getTriggerType() == TriggerType.HEARTBEAT && schedule.getHeartbeatIntervalSeconds() != null) {
            return Instant.now().plusSeconds(schedule.getHeartbeatIntervalSeconds());
        }
        if (schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank()) {
            ZoneId zoneId = ZoneId.of(schedule.getTimeZone() != null ? schedule.getTimeZone() : defaultTimeZone);
            return CronParser.computeNextFire(schedule.getCronExpression(), Instant.now(), zoneId);
        }
        return null;
    }

    private void validateSchedule(ScheduleConfiguration schedule) {
        if (schedule.getAgentId() == null || schedule.getAgentId().isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }

        // Validate time zone early — before cron parsing which also uses ZoneId.of()
        if (schedule.getTimeZone() != null && !schedule.getTimeZone().isBlank()) {
            try {
                ZoneId.of(schedule.getTimeZone());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid time zone: " + schedule.getTimeZone());
            }
        }

        // Infer trigger type: if heartbeatIntervalSeconds is set, treat as HEARTBEAT
        // regardless of the default value in ScheduleConfiguration
        TriggerType type = schedule.getTriggerType();
        if (type == null || schedule.getHeartbeatIntervalSeconds() != null) {
            type = schedule.getHeartbeatIntervalSeconds() != null ? TriggerType.HEARTBEAT : TriggerType.CRON;
        }

        if (type == TriggerType.HEARTBEAT) {
            // Heartbeat: must have interval
            if (schedule.getHeartbeatIntervalSeconds() == null || schedule.getHeartbeatIntervalSeconds() <= 0) {
                throw new IllegalArgumentException("heartbeatIntervalSeconds is required and must be > 0 for HEARTBEAT triggers");
            }
            // Enforce minimum interval
            if (schedule.getHeartbeatIntervalSeconds() < minIntervalSeconds) {
                throw new IllegalArgumentException(String.format("Heartbeat interval (%ds) is below minimum allowed (%ds)",
                        schedule.getHeartbeatIntervalSeconds(), minIntervalSeconds));
            }
            // Message is optional for heartbeats (will default to "heartbeat")
        } else {
            // CRON type: validate message required
            if (schedule.getMessage() == null || schedule.getMessage().isBlank()) {
                throw new IllegalArgumentException("message is required for CRON triggers");
            }

            // Exactly one of cron or oneTimeAt
            boolean hasCron = schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank();
            boolean hasOneTime = schedule.getOneTimeAt() != null && !schedule.getOneTimeAt().isBlank();
            if (!hasCron && !hasOneTime) {
                throw new IllegalArgumentException("Either cronExpression or oneTimeAt is required for CRON triggers");
            }
            if (hasCron && hasOneTime) {
                throw new IllegalArgumentException("Cannot set both cronExpression and oneTimeAt");
            }

            // Validate cron
            if (hasCron) {
                CronParser.validate(schedule.getCronExpression());

                // Enforce minimum interval
                ZoneId zoneId = ZoneId.of(schedule.getTimeZone() != null ? schedule.getTimeZone() : defaultTimeZone);
                long intervalSec = CronParser.computeMinIntervalSeconds(schedule.getCronExpression(), zoneId);
                if (intervalSec < minIntervalSeconds) {
                    throw new IllegalArgumentException(String.format(
                            "Cron interval (%ds) is below minimum allowed (%ds). "
                                    + "Use a less frequent schedule or contact admin to adjust eddi.schedule.min-interval-seconds",
                            intervalSec, minIntervalSeconds));
                }
            }
        }

        // Validate conversation strategy
        String strategy = schedule.getConversationStrategy();
        if (strategy != null && !strategy.isBlank() && !strategy.equals("new") && !strategy.equals("persistent")) {
            throw new IllegalArgumentException("conversationStrategy must be 'new' or 'persistent', got: " + strategy);
        }
    }

    private void enrichCronDescription(ScheduleConfiguration schedule) {
        if (schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank()) {
            schedule.setCronDescription(CronDescriber.describe(schedule.getCronExpression()));
        } else if (schedule.getTriggerType() == TriggerType.HEARTBEAT && schedule.getHeartbeatIntervalSeconds() != null) {
            schedule.setCronDescription(describeHeartbeat(schedule.getHeartbeatIntervalSeconds()));
        }
    }

    private static String describeHeartbeat(long seconds) {
        if (seconds < 60)
            return "Every " + seconds + " seconds";
        if (seconds < 3600) {
            long min = seconds / 60;
            return min == 1 ? "Every minute" : "Every " + min + " minutes";
        }
        if (seconds < 86400) {
            long hours = seconds / 3600;
            return hours == 1 ? "Every hour" : "Every " + hours + " hours";
        }
        long days = seconds / 86400;
        return days == 1 ? "Every day" : "Every " + days + " days";
    }
}
