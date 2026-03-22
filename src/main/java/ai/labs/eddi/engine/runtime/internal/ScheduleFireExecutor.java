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
 * Executes a scheduled fire by resolving the conversation strategy
 * and calling {@link IConversationService#say}.
 * <p>
 * All existing guards apply automatically:
 * <ul>
 *   <li>{@code TenantQuotaService} — API call and cost quotas</li>
 *   <li>{@code AuditLedgerService} — HMAC-SHA256 audit trail</li>
 *   <li>{@code ConversationCoordinator} — ordered processing</li>
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

    /**
     * Execute a schedule fire. Returns the fire log entry.
     *
     * @param schedule      the schedule to fire
     * @param instanceId    this cluster instance's ID
     * @param attemptNumber which retry attempt (1-based), passed by caller
     *                      to avoid stale snapshot issues
     * @return the completed fire log
     */
    public ScheduleFireLog fire(ScheduleConfiguration schedule, String instanceId, int attemptNumber) {
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
            //    This enforces tenant quotas, audit trail, conversation ordering
            var latch = new CountDownLatch(1);
            conversationService.say(
                    env,
                    schedule.getAgentId(),
                    conversationId,
                    false,         // returnDetailed
                    true,          // returnCurrentStepOnly
                    List.of(),     // returningFields (empty = all)
                    inputData,
                    false,         // rerunOnly
                    snapshot -> latch.countDown() // responseHandler
            );

            // Wait for workflow completion (max 5 minutes)
            if (!latch.await(5, TimeUnit.MINUTES)) {
                throw new RuntimeException("Schedule fire timed out after 5 minutes");
            }

            status = ScheduleConfiguration.FireStatus.COMPLETED.name();
            LOGGER.infof("[SCHEDULE] Fired schedule '%s' (id=%s, type=%s) for Agent %s → conversation %s",
                    schedule.getName(), schedule.getId(), schedule.getTriggerType(),
                    schedule.getAgentId(), conversationId);

        } catch (Exception e) {
            status = ScheduleConfiguration.FireStatus.FAILED.name();
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.warnf(e, "[SCHEDULE] Fire failed for schedule '%s' (id=%s): %s",
                    schedule.getName(), schedule.getId(), errorMessage);
        }

        // 4. Log the fire attempt (Fix #4: use caller-provided attemptNumber)
        ScheduleFireLog fireLog = new ScheduleFireLog(
                fireLogId,
                schedule.getId(),
                schedule.getFireId(),
                schedule.getNextFire(),
                startedAt,
                Instant.now(),
                status,
                instanceId,
                conversationId,
                errorMessage,
                attemptNumber,
                cost
        );

        try {
            scheduleStore.logFire(fireLog);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to log fire for schedule %s", schedule.getId());
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
        var userId = schedule.getUserId() != null
                ? schedule.getUserId() : "system:scheduler";

        var result = conversationService.startConversation(
                env,
                schedule.getAgentId(),
                userId,
                Collections.emptyMap()
        );

        return result.conversationId();
    }

    private String resolveOrCreatePersistent(ScheduleConfiguration schedule, Environment env) throws Exception {
        String conversationId = schedule.getPersistentConversationId();

        if (conversationId != null && !conversationId.isBlank()) {
            // Validate conversation still exists and is usable
            try {
                conversationService.readConversation(
                        env,
                        schedule.getAgentId(),
                        conversationId,
                        false, true, List.of()
                );
                return conversationId;
            } catch (Exception e) {
                LOGGER.infof("[SCHEDULE] Persistent conversation %s no longer valid for schedule %s, creating new",
                        conversationId, schedule.getId());
            }
        }

        // Create new and update the schedule
        String newConversationId = createNewConversation(schedule, env);
        schedule.setPersistentConversationId(newConversationId);
        try {
            scheduleStore.updateSchedule(schedule.getId(), schedule);
        } catch (Exception e) {
            LOGGER.warnf(e, "[SCHEDULE] Failed to update persistent conversation ID on schedule %s",
                    schedule.getId());
        }
        return newConversationId;
    }

    private InputData buildInputData(ScheduleConfiguration schedule) {
        InputData inputData = new InputData();

        // For heartbeats, default message to "heartbeat" if unset
        String message = schedule.getMessage();
        if ((message == null || message.isBlank())
                && schedule.getTriggerType() == TriggerType.HEARTBEAT) {
            message = "heartbeat";
        }
        inputData.setInput(message);

        Map<String, Context> contextMap = new HashMap<>();

        // Inject schedule context so the Agent knows this is a scheduled trigger
        Map<String, Object> scheduleContext = new LinkedHashMap<>();
        scheduleContext.put("trigger", schedule.getTriggerType() == TriggerType.HEARTBEAT
                ? "heartbeat" : "scheduled");
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
