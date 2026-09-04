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
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.internal.HitlTimeoutHandler;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final Duration DEFAULT_FIRE_TIMEOUT = Duration.ofMinutes(5);

    @Inject
    IConversationService conversationService;

    @Inject
    IScheduleStore scheduleStore;

    @Inject
    HitlTimeoutHandler hitlTimeoutHandler;

    @Inject
    DreamService dreamService;

    @Inject
    TeamCadenceService teamCadenceService;

    @Inject
    ToolCostTracker toolCostTracker;

    /**
     * How long a single conversation fire may take before it is abandoned as
     * failed.
     * <p>
     * Defaults to the same 5 minutes the hard-coded constant used, and to the same
     * value as {@code eddi.schedule.lease-timeout} — the window after which another
     * instance may reclaim the schedule anyway, so waiting longer than the lease
     * serves no purpose. A deployment that raises the lease can now raise this too.
     */
    @ConfigProperty(name = "eddi.schedule.fire-timeout", defaultValue = "5m")
    Duration fireTimeout = DEFAULT_FIRE_TIMEOUT;

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
        if (HitlSchedules.isHitlTimeout(md)) {
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
                    (String) md.get(HitlSchedules.METADATA_CONVERSATION_ID_KEY),
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

        if (TeamCadenceService.isTeamCadenceSchedule(md)) {
            // Team cadence fast-path (I13) — a backlog pull into a group
            // discussion, not a conversation turn. Same reasoning and machinery
            // as the Dream fast-path above.
            return fireTeamCadence(schedule, instanceId, attemptNumber);
        }

        Instant startedAt = Instant.now();
        String fireLogId = UUID.randomUUID().toString();
        String conversationId = null;
        String errorMessage = null;
        String status;
        double cost = 0.0;
        boolean interrupted = false;
        double costBefore = 0.0;

        try {
            Environment env = resolveEnvironment(schedule.getEnvironment());

            // 1. Resolve conversation
            conversationId = resolveConversation(schedule, env);

            // The fire log's cost column was hard-wired to 0.0 on this path, so the
            // number operators (and Dream) read for a scheduled agent was meaningless.
            // Take a DELTA rather than the running total: a conversationStrategy=
            // persistent schedule reuses one conversation across every fire, so its
            // accumulated total would grow monotonically and attribute the whole
            // history to whichever fire happened to read it.
            costBefore = conversationCost(conversationId);

            // 2. Build InputData with scheduled context
            InputData inputData = buildInputData(schedule);

            // 3. Execute via ConversationService.say()
            // This enforces tenant quotas, audit trail, conversation ordering
            var latch = new CountDownLatch(1);
            // The outcome must come from the SNAPSHOT, not from the latch. The latch is
            // counted down from Conversation.runStep's finally block, which also runs on
            // the failure branch, and ConversationService.say swallows the
            // LifecycleException — so "the handler was called" only means the turn was
            // attempted. Reporting COMPLETED there made every in-pipeline failure (LLM
            // outage, tool error, unresolvable workflow config) look like a green fire:
            // failCount never incremented, backoff never applied, nothing ever
            // dead-lettered.
            var outcome = new AtomicReference<SimpleConversationMemorySnapshot>();
            conversationService.say(env, schedule.getAgentId(), conversationId, false, // returnDetailed
                    true, // returnCurrentStepOnly
                    List.of(), // returningFields (empty = all)
                    inputData, false, // rerunOnly
                    snapshot -> { // responseHandler
                        outcome.set(snapshot);
                        latch.countDown();
                    });

            Duration timeout = fireTimeout != null ? fireTimeout : DEFAULT_FIRE_TIMEOUT;
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Schedule fire timed out after " + timeout);
            }

            SimpleConversationMemorySnapshot snapshot = outcome.get();
            ConversationState state = snapshot != null ? snapshot.getConversationState() : null;
            if (state == ConversationState.ERROR) {
                status = ScheduleConfiguration.FireStatus.FAILED.name();
                errorMessage = "Conversation ended in state " + state;
                LOGGER.warnf("[SCHEDULE] Fire of schedule '%s' (id=%s) left conversation %s in state %s", schedule.getName(),
                        schedule.getId(), conversationId, state);
            } else {
                status = ScheduleConfiguration.FireStatus.COMPLETED.name();
                LOGGER.infof("[SCHEDULE] Fired schedule '%s' (id=%s, type=%s) for Agent %s → conversation %s", schedule.getName(),
                        schedule.getId(), schedule.getTriggerType(), schedule.getAgentId(), conversationId);
            }

        } catch (Exception e) {
            // B2: latch.await() above CLEARS the interrupt flag when it throws
            // InterruptedException, and this broad catch would otherwise swallow the
            // cancellation signal entirely. Only REMEMBER it here — re-asserting the
            // flag now would break the fire log below (see the finally).
            if (e instanceof InterruptedException) {
                interrupted = true;
            }
            status = ScheduleConfiguration.FireStatus.FAILED.name();
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.warnf(e, "[SCHEDULE] Fire failed for schedule '%s' (id=%s): %s", schedule.getName(), schedule.getId(), errorMessage);
        }

        // Charged whether the fire succeeded or failed: a turn that errored after
        // calling three tools still cost money, and a cost of 0.0 on every failure
        // would hide exactly the schedules worth investigating.
        cost = Math.max(0.0, conversationCost(conversationId) - costBefore);

        // 4. Log the fire attempt (Fix #4: use caller-provided attemptNumber)
        ScheduleFireLog fireLog = new ScheduleFireLog(fireLogId, schedule.getId(), schedule.getFireId(), schedule.getNextFire(), startedAt,
                Instant.now(), status, instanceId, conversationId, errorMessage, attemptNumber, cost);

        try {
            scheduleStore.logFire(fireLog);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to log fire for schedule %s", schedule.getId());
        } finally {
            restoreInterrupt(interrupted);
        }

        return fireLog;
    }

    /**
     * Tool spend accumulated so far on a conversation, or {@code 0.0} when nothing
     * has been tracked for it (a brand-new conversation, or a turn that called no
     * tools). Never throws — a fire must not fail because its accounting did.
     */
    private double conversationCost(String conversationId) {
        if (conversationId == null) {
            return 0.0;
        }
        try {
            var metrics = toolCostTracker.getConversationCosts(conversationId);
            return metrics != null ? metrics.getTotalCost() : 0.0;
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "[SCHEDULE] Could not read tool cost for conversation %s", conversationId);
            return 0.0;
        }
    }

    /**
     * Re-assert an interrupt that was consumed by a blocking call inside this fire,
     * so the cancellation signal still reaches the caller.
     * <p>
     * Ordering matters: this MUST run only after the store round trips this method
     * owns. The synchronous MongoDB driver checks out a connection with
     * {@code lockInterruptibly()} and aborts with {@code MongoInterruptedException}
     * when the calling thread's flag is already set, so restoring the flag before
     * {@code logFire} would destroy the FAILED fire log that the interrupt path
     * exists to write — leaving the attempt invisible on exactly the path where it
     * matters most.
     */
    private static void restoreInterrupt(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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
        boolean interrupted = false;

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
            // Same B2 reasoning — and the same ordering — as fire() above, repeated here
            // because this catch is just as broad: a blocking call inside Dream
            // consolidation CLEARS the interrupt flag when it throws
            // InterruptedException. Remember it and re-assert it in the finally below,
            // AFTER the fire log is written.
            if (e instanceof InterruptedException) {
                interrupted = true;
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
        } finally {
            restoreInterrupt(interrupted);
        }
        return fireLog;
    }

    /**
     * Run one team-cadence pull (I13) and record the fire. Mirrors
     * {@link #fireDreamConsolidation}: a deliberate skip (backlog empty, previous
     * discussion still running, lost claim) is COMPLETED with the reason in the
     * log; a real failure is FAILED so it retries with backoff and eventually
     * dead-letters instead of appearing to run while doing nothing.
     */
    private ScheduleFireLog fireTeamCadence(ScheduleConfiguration schedule, String instanceId, int attemptNumber) {
        Instant startedAt = Instant.now();
        String status;
        String errorMessage = null;
        String conversationId = null;
        boolean interrupted = false;

        try {
            TeamCadenceService.CadenceResult result = teamCadenceService.processScheduledFire(schedule.getMetadata());
            conversationId = result.discussionId();
            if (result.isSuccess()) {
                status = ScheduleConfiguration.FireStatus.COMPLETED.name();
                if (result.skippedReason() != null) {
                    LOGGER.infof("[SCHEDULE] Team cadence '%s' (id=%s) skipped: %s", schedule.getName(), schedule.getId(),
                            result.skippedReason());
                } else {
                    LOGGER.infof("[SCHEDULE] Team cadence '%s' (id=%s) started discussion %s with %d task(s)",
                            schedule.getName(), schedule.getId(), result.discussionId(), result.tasksPulled());
                }
            } else {
                status = ScheduleConfiguration.FireStatus.FAILED.name();
                errorMessage = result.error();
                LOGGER.errorf("[SCHEDULE] Team cadence failed for schedule '%s' (id=%s): %s", schedule.getName(),
                        schedule.getId(), errorMessage);
            }
        } catch (Exception e) {
            // Same B2 interrupt reasoning — and the same ordering — as fire() above.
            if (e instanceof InterruptedException) {
                interrupted = true;
            }
            status = ScheduleConfiguration.FireStatus.FAILED.name();
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.errorf(e, "[SCHEDULE] Team cadence threw for schedule '%s' (id=%s)", schedule.getName(), schedule.getId());
        }

        var fireLog = new ScheduleFireLog(UUID.randomUUID().toString(), schedule.getId(), schedule.getFireId(),
                schedule.getNextFire(), startedAt, Instant.now(), status, instanceId, conversationId, errorMessage,
                attemptNumber, 0.0);
        try {
            scheduleStore.logFire(fireLog);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to log team-cadence fire for schedule %s", schedule.getId());
        } finally {
            restoreInterrupt(interrupted);
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
        var userId = schedule.getUserId() != null ? schedule.getUserId() : DreamService.SCHEDULER_PLACEHOLDER_USER_ID;

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

        // Create new and record it with a SINGLE-FIELD write.
        //
        // Never updateSchedule() from here. `schedule` is the copy findDueSchedules
        // returned BEFORE tryClaim ran, so its fireStatus is still PENDING, its claim
        // columns are empty and its nextFire is still the past due time. Writing the
        // whole object back therefore un-claimed the row in the middle of its own fire:
        // the next poll (15s by default, while a fire may run for minutes) matched it
        // again, claimed it again, and pushed a second concurrent turn into this very
        // same persistent conversation.
        String newConversationId = createNewConversation(schedule, env);
        schedule.setPersistentConversationId(newConversationId);
        try {
            scheduleStore.setPersistentConversationId(schedule.getId(), newConversationId);
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
        String userId = schedule.getUserId() != null ? schedule.getUserId() : DreamService.SCHEDULER_PLACEHOLDER_USER_ID;
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
