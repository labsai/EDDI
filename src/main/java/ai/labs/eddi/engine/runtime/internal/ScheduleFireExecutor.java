/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Executes a scheduled fire by resolving the conversation strategy and calling
 * {@link IConversationService#say}.
 * <p>
 * Two schedule kinds are recognised by their metadata and bypass the
 * conversation path entirely, because neither is a conversation turn:
 * <ul>
 * <li>{@code hitlType=hitl_timeout} — HITL approval deadlines
 * ({@code HitlTimeoutHandler})</li>
 * <li>{@code dreamType=dream_consolidation} — background user-memory
 * maintenance ({@link DreamService})</li>
 * </ul>
 * Both still run under the poller's cluster-wide claim, lease, retry/backoff
 * and fire-log machinery.
 * <p>
 * All existing guards apply automatically:
 * <ul>
 * <li>{@code TenantQuotaService} — API call and cost quotas</li>
 * <li>{@code AuditLedgerService} — HMAC-SHA256 audit trail</li>
 * <li>{@code ConversationCoordinator} — ordered processing</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class ScheduleFireExecutor {

    private static final Logger LOGGER = Logger.getLogger(ScheduleFireExecutor.class);

    @Inject
    IConversationService conversationService;

    @Inject
    IScheduleStore scheduleStore;

    @Inject
    ai.labs.eddi.engine.internal.HitlTimeoutHandler hitlTimeoutHandler;

    @Inject
    DreamService dreamService;

    /**
     * Execute a schedule fire. Returns the fire log entry.
     *
     * @param schedule
     *            the schedule to fire
     * @param instanceId
     *            this cluster instance's ID
     * @param attemptNumber
     *            which retry attempt (1-based), passed by caller to avoid stale
     *            snapshot issues
     * @return the completed fire log
     */
    public ScheduleFireLog fire(ScheduleConfiguration schedule, String instanceId, int attemptNumber) {
        Map<String, Object> md = schedule.getMetadata();
        if (ai.labs.eddi.engine.hitl.HitlSchedules.isHitlTimeout(md)) {
            // HITL timeout fast-path — isolate exceptions and record a fire log for
            // parity with the normal path (observability + retry diagnostics).
            Instant hitlStartedAt = Instant.now();
            String hitlStatus = ScheduleConfiguration.FireStatus.COMPLETED.name();
            String hitlError = null;
            try {
                hitlTimeoutHandler.handleTimeout(md);
            } catch (Exception e) {
                hitlStatus = ScheduleConfiguration.FireStatus.FAILED.name();
                hitlError = e.getClass().getSimpleName() + ": " + e.getMessage();
                LOGGER.warnf(e, "[SCHEDULE] HITL timeout handling failed for schedule %s", schedule.getId());
            }
            var hitlFireLog = new ScheduleFireLog(UUID.randomUUID().toString(), schedule.getId(),
                    schedule.getFireId(), schedule.getNextFire(), hitlStartedAt, Instant.now(),
                    hitlStatus, instanceId,
                    (String) md.get(ai.labs.eddi.engine.hitl.HitlSchedules.METADATA_CONVERSATION_ID_KEY),
                    hitlError, attemptNumber, 0.0);
            try {
                scheduleStore.logFire(hitlFireLog);
            } catch (Exception e) {
                LOGGER.errorf(e, "[SCHEDULE] Failed to log HITL timeout fire for schedule %s", schedule.getId());
            }
            return hitlFireLog;
        }

        if (DreamService.isDreamSchedule(md)) {
            // Dream consolidation fast-path — a maintenance job over the user's
            // persistent memories, not a conversation turn, so it never goes
            // through say(). Everything else the schedule machinery provides
            // (cluster-wide CAS claim, lease, retry/backoff, dead-lettering, fire
            // log) applies unchanged.
            return fireDreamConsolidation(schedule, instanceId, attemptNumber);
        }

        Instant startedAt = Instant.now();
        String fireLogId = UUID.randomUUID().toString();
        String conversationId = null;
        String errorMessage = null;
        String status;
        double cost = 0.0;

        try {
            Environment env = resolveEnvironment(schedule.getEnvironment());

            // 1. Resolve conversation
            conversationId = resolveConversation(schedule, env);

            // 2. Build InputData with scheduled context
            InputData inputData = buildInputData(schedule);

            // 3. Execute via ConversationService.say()
            // This enforces tenant quotas, audit trail, conversation ordering
            var latch = new CountDownLatch(1);
            conversationService.say(env, schedule.getAgentId(), conversationId, false, // returnDetailed
                    true, // returnCurrentStepOnly
                    List.of(), // returningFields (empty = all)
                    inputData, false, // rerunOnly
                    snapshot -> latch.countDown() // responseHandler
            );

            // Wait for workflow completion (max 5 minutes)
            if (!latch.await(5, TimeUnit.MINUTES)) {
                throw new RuntimeException("Schedule fire timed out after 5 minutes");
            }

            status = ScheduleConfiguration.FireStatus.COMPLETED.name();
            LOGGER.infof("[SCHEDULE] Fired schedule '%s' (id=%s, type=%s) for Agent %s → conversation %s", schedule.getName(), schedule.getId(),
                    schedule.getTriggerType(), schedule.getAgentId(), conversationId);

        } catch (Exception e) {
            // B2: latch.await() above CLEARS the interrupt flag when it throws
            // InterruptedException, and this broad catch would otherwise swallow the
            // poller thread's shutdown signal — it would keep firing further schedules
            // while the executor is shutting down. Restore it before continuing; the
            // fire is still logged FAILED below so the attempt stays visible.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            status = ScheduleConfiguration.FireStatus.FAILED.name();
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.warnf(e, "[SCHEDULE] Fire failed for schedule '%s' (id=%s): %s", schedule.getName(), schedule.getId(), errorMessage);
        }

        // 4. Log the fire attempt (Fix #4: use caller-provided attemptNumber)
        ScheduleFireLog fireLog = new ScheduleFireLog(fireLogId, schedule.getId(), schedule.getFireId(), schedule.getNextFire(), startedAt,
                Instant.now(), status, instanceId, conversationId, errorMessage, attemptNumber, cost);

        try {
            scheduleStore.logFire(fireLog);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to log fire for schedule %s", schedule.getId());
        }

        return fireLog;
    }

    /**
     * Run one Dream memory-consolidation cycle for the schedule's user and record
     * the fire.
     * <p>
     * A rejected or failed cycle is logged FAILED rather than swallowed, so a
     * misconfigured dream schedule surfaces in the fire log, retries with backoff
     * and eventually dead-letters instead of appearing to run while doing nothing.
     */
    private ScheduleFireLog fireDreamConsolidation(ScheduleConfiguration schedule, String instanceId, int attemptNumber) {
        Instant startedAt = Instant.now();
        String status;
        String errorMessage = null;
        double cost = 0.0;

        try {
            // userId is passed through unchanged — DreamService rejects a missing or
            // placeholder identity loudly; defaulting it here would silently
            // consolidate an empty memory set.
            DreamService.DreamResult result = dreamService.processScheduledFire(
                    schedule.getAgentId(), schedule.getAgentVersion(), schedule.getUserId());
            cost = result.estimatedCostUsd();
            if (result.isSuccess()) {
                status = ScheduleConfiguration.FireStatus.COMPLETED.name();
                LOGGER.infof("[SCHEDULE] Dream consolidation for schedule '%s' (id=%s, agent=%s, user=%s): "
                        + "pruned=%d, contradictions=%d, summarized=%d, estimatedCost=$%.4f", schedule.getName(), schedule.getId(),
                        schedule.getAgentId(), schedule.getUserId(), result.entriesPruned(), result.contradictionsFound(),
                        result.entriesSummarized(), cost);
            } else {
                status = ScheduleConfiguration.FireStatus.FAILED.name();
                errorMessage = result.error();
                LOGGER.errorf("[SCHEDULE] Dream consolidation failed for schedule '%s' (id=%s): %s", schedule.getName(), schedule.getId(),
                        errorMessage);
            }
        } catch (Exception e) {
            // Same B2 reasoning as fire() above, and it has to be repeated here because
            // this catch is just as broad: a blocking call inside Dream consolidation
            // CLEARS the interrupt flag when it throws InterruptedException, so
            // swallowing it would leave the poller thread running further schedules
            // through a shutdown. The fix landing on only one of two sibling catches in
            // the same class is exactly how these gaps happen.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            status = ScheduleConfiguration.FireStatus.FAILED.name();
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.errorf(e, "[SCHEDULE] Dream consolidation threw for schedule '%s' (id=%s)", schedule.getName(), schedule.getId());
        }

        var fireLog = new ScheduleFireLog(UUID.randomUUID().toString(), schedule.getId(), schedule.getFireId(), schedule.getNextFire(), startedAt,
                Instant.now(), status, instanceId, null, errorMessage, attemptNumber, cost);
        try {
            scheduleStore.logFire(fireLog);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to log dream fire for schedule %s", schedule.getId());
        }
        return fireLog;
    }

    private String resolveConversation(ScheduleConfiguration schedule, Environment env) throws Exception {
        String strategy = schedule.getConversationStrategy();
        if (strategy == null) {
            // Default: heartbeats use persistent, cron uses new
            strategy = schedule.getTriggerType() == TriggerType.HEARTBEAT ? "persistent" : "new";
        }

        return switch (strategy) {
            case "persistent" -> resolveOrCreatePersistent(schedule, env);
            default -> createNewConversation(schedule, env); // "new" or unknown
        };
    }

    private String createNewConversation(ScheduleConfiguration schedule, Environment env) throws Exception {
        var userId = schedule.getUserId() != null ? schedule.getUserId() : "system:scheduler";

        var result = conversationService.startConversation(env, schedule.getAgentId(), userId, Collections.emptyMap());

        return result.conversationId();
    }

    private String resolveOrCreatePersistent(ScheduleConfiguration schedule, Environment env) throws Exception {
        String conversationId = schedule.getPersistentConversationId();

        if (conversationId != null && !conversationId.isBlank()) {
            // Validate conversation still exists and is usable
            try {
                conversationService.readConversation(env, schedule.getAgentId(), conversationId, false, true, List.of());
                return conversationId;
            } catch (Exception e) {
                LOGGER.infof("[SCHEDULE] Persistent conversation %s no longer valid for schedule %s, creating new", conversationId, schedule.getId());
            }
        }

        // Create new and update the schedule
        String newConversationId = createNewConversation(schedule, env);
        schedule.setPersistentConversationId(newConversationId);
        try {
            scheduleStore.updateSchedule(schedule.getId(), schedule);
        } catch (Exception e) {
            LOGGER.warnf(e, "[SCHEDULE] Failed to update persistent conversation ID on schedule %s", schedule.getId());
        }
        return newConversationId;
    }

    private InputData buildInputData(ScheduleConfiguration schedule) {
        InputData inputData = new InputData();

        // For heartbeats, default message to "heartbeat" if unset
        String message = schedule.getMessage();
        if ((message == null || message.isBlank()) && schedule.getTriggerType() == TriggerType.HEARTBEAT) {
            message = "heartbeat";
        }
        inputData.setInput(message);

        Map<String, Context> contextMap = new HashMap<>();

        // Inject schedule context so the Agent knows this is a scheduled trigger
        Map<String, Object> scheduleContext = new LinkedHashMap<>();
        scheduleContext.put("trigger", schedule.getTriggerType() == TriggerType.HEARTBEAT ? "heartbeat" : "scheduled");
        scheduleContext.put("triggerType", schedule.getTriggerType().name());
        scheduleContext.put("scheduleId", schedule.getId());
        scheduleContext.put("scheduleName", schedule.getName());
        scheduleContext.put("fireId", schedule.getFireId());
        scheduleContext.put("fireTime", schedule.getNextFire() != null ? schedule.getNextFire().toString() : null);
        if (schedule.getCronExpression() != null) {
            scheduleContext.put("cronExpression", schedule.getCronExpression());
        }
        if (schedule.getHeartbeatIntervalSeconds() != null) {
            scheduleContext.put("heartbeatIntervalSeconds", schedule.getHeartbeatIntervalSeconds());
        }

        contextMap.put("schedule", new Context(Context.ContextType.object, scheduleContext));

        // Set userId context
        String userId = schedule.getUserId() != null ? schedule.getUserId() : "system:scheduler";
        contextMap.put("userId", new Context(Context.ContextType.string, userId));

        inputData.setContext(contextMap);
        return inputData;
    }

    private static Environment resolveEnvironment(String envStr) {
        if (envStr == null || envStr.isBlank()) {
            return Environment.production;
        }
        try {
            return Environment.valueOf(envStr.toLowerCase());
        } catch (IllegalArgumentException e) {
            return Environment.production;
        }
    }
}
