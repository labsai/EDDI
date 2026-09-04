/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls the schedule store for due schedules and fires them.
 * <p>
 * Uses atomic CAS claiming so exactly one instance owns a schedule per fire.
 * Delivery is <strong>at-least-once</strong>, not exactly-once: a claim's lease
 * can expire and be stolen by another instance (see
 * {@link IScheduleStore#tryClaim}) while the original, possibly-wedged fire may
 * still commit, so a fire can run more than once. Fire targets are therefore
 * expected to be idempotent — HITL timeout fires resolve via a state CAS
 * (resume/cancel) and no-op on a duplicate. Implements exponential backoff on
 * failure with dead-lettering after max retries.
 * <p>
 * Supports two trigger types:
 * <ul>
 * <li>{@code CRON} — wall-clock aligned via cron expression</li>
 * <li>{@code HEARTBEAT} — interval-based and drift-proof: nextFire is the fire
 * time that was DUE plus the interval, not the moment the fire finished, so the
 * duration of a turn does not push the cadence out</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class SchedulePollerService {

    private static final Logger LOGGER = Logger.getLogger(SchedulePollerService.class);

    // Fix #10: Constructor injection for testability
    private final IScheduleStore scheduleStore;
    private final ScheduleFireExecutor fireExecutor;
    private final MeterRegistry meterRegistry;

    private final boolean schedulingEnabled;
    private final Duration leaseTimeout;
    private final int maxRetries;
    private final int backoffBaseSeconds;
    private final int backoffMultiplier;
    private final Optional<String> configuredInstanceId;
    private final String defaultTimeZone;
    private final Duration fireLogRetention;

    private String instanceId;
    private Counter pollCounter;
    private Counter fireCounter;
    private Counter fireFailedCounter;
    private Counter claimConflictCounter;
    private Counter deadLetterCounter;
    private Counter fireLogsPrunedCounter;
    private Timer fireDurationTimer;

    @Inject
    public SchedulePollerService(IScheduleStore scheduleStore, ScheduleFireExecutor fireExecutor, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.schedule.enabled", defaultValue = "true") boolean schedulingEnabled,
            @ConfigProperty(name = "eddi.schedule.lease-timeout", defaultValue = "5m") Duration leaseTimeout,
            @ConfigProperty(name = "eddi.schedule.max-retries", defaultValue = "5") int maxRetries,
            @ConfigProperty(name = "eddi.schedule.backoff-base-seconds", defaultValue = "15") int backoffBaseSeconds,
            @ConfigProperty(name = "eddi.schedule.backoff-multiplier", defaultValue = "4") int backoffMultiplier,
            @ConfigProperty(name = "eddi.schedule.instance-id") Optional<String> configuredInstanceId,
            @ConfigProperty(name = "eddi.schedule.default-timezone", defaultValue = "UTC") String defaultTimeZone,
            @ConfigProperty(name = "eddi.schedule.fire-log-retention", defaultValue = "90d") Duration fireLogRetention) {
        this.scheduleStore = scheduleStore;
        this.fireExecutor = fireExecutor;
        this.meterRegistry = meterRegistry;
        this.schedulingEnabled = schedulingEnabled;
        this.leaseTimeout = leaseTimeout;
        this.maxRetries = maxRetries;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.backoffMultiplier = backoffMultiplier;
        this.configuredInstanceId = configuredInstanceId;
        this.defaultTimeZone = defaultTimeZone;
        this.fireLogRetention = fireLogRetention;
    }

    @PostConstruct
    void init() {
        // Resolve instance ID
        if (configuredInstanceId.isPresent() && !configuredInstanceId.get().isBlank()) {
            instanceId = configuredInstanceId.get();
        } else {
            try {
                instanceId = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                instanceId = "instance-" + ProcessHandle.current().pid();
            }
        }

        // Metrics
        pollCounter = meterRegistry.counter("eddi.schedule.poll.count");
        fireCounter = meterRegistry.counter("eddi.schedule.fire.count");
        fireFailedCounter = meterRegistry.counter("eddi.schedule.fire.failed");
        claimConflictCounter = meterRegistry.counter("eddi.schedule.claim.conflict");
        deadLetterCounter = meterRegistry.counter("eddi.schedule.fire.deadlettered");
        fireLogsPrunedCounter = meterRegistry.counter("eddi.schedule.firelog.pruned");
        fireDurationTimer = meterRegistry.timer("eddi.schedule.fire.duration");

        if (schedulingEnabled) {
            LOGGER.infof("Schedule poller initialized (instance=%s, leaseTimeout=%s, maxRetries=%d)", instanceId, leaseTimeout, maxRetries);
        } else {
            LOGGER.info("Schedule poller DISABLED (eddi.schedule.enabled=false)");
        }
    }

    /**
     * Main poll loop — runs at the configured interval. Finds due schedules, claims
     * them atomically, and fires.
     */
    @Scheduled(every = "${eddi.schedule.poll-interval:15s}", identity = "schedule-poller")
    void pollDueSchedules() {
        if (!schedulingEnabled) {
            return;
        }

        pollCounter.increment();

        try {
            Instant now = Instant.now();
            Instant leaseExpiry = now.minus(leaseTimeout);

            List<ScheduleConfiguration> dueSchedules = scheduleStore.findDueSchedules(now, leaseExpiry, maxRetries);

            if (!dueSchedules.isEmpty()) {
                LOGGER.debugf("[SCHEDULE] Found %d due schedules", dueSchedules.size());
            }

            // Claim BEFORE dispatch: the cluster-wide CAS claim must run on the poll
            // thread so exactly one instance owns each schedule. Only the schedules
            // this instance won are dispatched.
            List<ScheduleConfiguration> claimed = new ArrayList<>();
            for (ScheduleConfiguration schedule : dueSchedules) {
                if (claimSchedule(schedule, now, leaseExpiry)) {
                    claimed.add(schedule);
                }
            }

            // Fire claimed schedules concurrently on virtual threads with per-fire
            // error isolation: a large burst of one-shot HITL timeouts (each doing a
            // synchronous snapshot load) no longer serializes behind one thread and
            // starves other schedule types (Dream, maintenance) for the poll cycle.
            dispatchClaimed(claimed);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Poll cycle failed");
        }
    }

    /**
     * Retention sweep for the fire log.
     * <p>
     * Nothing pruned {@code eddi_schedule_fire_logs}: a 60-second heartbeat writes
     * ~525,600 rows a year on its own, every HITL pause adds a one-shot schedule
     * whose log outlives it, and {@code readFailedFireLogs} scans the lot. This is
     * the configurable cap AGENTS.md §4.7 asks for — set
     * {@code eddi.schedule.fire-log-retention} to {@code 0} (or a negative
     * duration) to keep everything.
     * <p>
     * Deliberately a second method on the existing poller rather than a new
     * scheduler, and deliberately NOT guarded by a cluster claim: a DELETE by
     * timestamp is idempotent, so several instances running it concurrently is
     * merely redundant, never wrong.
     */
    @Scheduled(every = "${eddi.schedule.fire-log-prune-interval:1h}", identity = "schedule-fire-log-prune",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pruneFireLogs() {
        if (!schedulingEnabled || fireLogRetention == null || fireLogRetention.isZero() || fireLogRetention.isNegative()) {
            return;
        }
        try {
            int deleted = scheduleStore.deleteFireLogsOlderThan(Instant.now().minus(fireLogRetention));
            if (deleted > 0) {
                fireLogsPrunedCounter.increment(deleted);
                LOGGER.infof("[SCHEDULE] Pruned %d fire log(s) older than %s", deleted, fireLogRetention);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Fire log retention sweep failed");
        }
    }

    /**
     * Atomically claim a schedule for this instance. Returns true only if the CAS
     * claim succeeded.
     */
    private boolean claimSchedule(ScheduleConfiguration schedule, Instant now, Instant leaseExpiry) {
        try {
            boolean claimed = scheduleStore.tryClaim(schedule.getId(), instanceId, now, leaseExpiry);
            if (!claimed) {
                claimConflictCounter.increment();
                LOGGER.debugf("[SCHEDULE] Claim conflict for schedule %s — another instance got it", schedule.getId());
                return false;
            }
            // tryClaim() returns only a boolean; it does not hand back the fireId it
            // just persisted. Without this, the in-memory ScheduleConfiguration keeps
            // its stale pre-claim fireId (often null), which ScheduleFireExecutor uses
            // for fire-log correlation and injects into the agent context — the fired
            // turn couldn't be correlated to the claimed DB row. Both MongoScheduleStore
            // and PostgresScheduleStore derive the persisted fireId identically as
            // `scheduleId + "_" + now` — mirror that here to keep the in-memory copy in
            // sync with what was actually written.
            schedule.setFireId(IScheduleStore.fireIdOf(schedule.getId(), now));
            return true;
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Error claiming schedule %s", schedule.getId());
            return false;
        }
    }

    /**
     * Fire all claimed schedules on a virtual-thread-per-task executor and wait for
     * the batch to finish within the poll cycle. Each fire is isolated so one
     * failure never blocks the rest of the batch.
     */
    private void dispatchClaimed(List<ScheduleConfiguration> claimed) {
        if (claimed.isEmpty()) {
            return;
        }
        // Deliberately NOT try-with-resources: ExecutorService.close() awaits
        // termination indefinitely, and future.cancel(true) only unblocks a task that
        // honors Thread.interrupt(). The real fire path (say() → synchronous DB driver
        // calls) can stall on a NON-interruptible socket read, which close() would then
        // wait on forever — pinning this @Scheduled poll thread and stopping this
        // instance from claiming or firing ANY further schedule (HITL timeouts, Dream,
        // maintenance). That is the exact hang the per-fire timeout exists to prevent.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<?>> futures = new ArrayList<>(claimed.size());
            for (ScheduleConfiguration schedule : claimed) {
                futures.add(executor.submit(() -> fireClaimedSchedule(schedule)));
            }
            // Bound the WHOLE batch by ONE shared deadline, not leaseTimeout per
            // future: a per-future bound in this sequential loop would let N stalled
            // fires pin the poll thread for up to N*leaseTimeout (hours for a large
            // batch), defeating the point of the timeout. leaseTimeout is the window
            // after which another instance may reclaim these schedules anyway
            // (findDueSchedules' leaseExpiredFilter), so waiting longer serves no
            // purpose. On per-future timeout, cancel (best-effort interrupt) and move on.
            long deadlineNanos = System.nanoTime() + Math.max(leaseTimeout.toNanos(), 1_000_000L);
            for (Future<?> future : futures) {
                long remainingMs = Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                try {
                    future.get(remainingMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    LOGGER.errorf("[SCHEDULE] Dispatched fire task exceeded the batch lease deadline (%s) — cancelling; "
                            + "the schedule will become reclaimable once its lease expires", leaseTimeout);
                } catch (Exception e) {
                    LOGGER.errorf(e, "[SCHEDULE] Dispatched fire task failed unexpectedly");
                }
            }
        } finally {
            // shutdownNow() interrupts any still-running task (best effort) and returns
            // immediately WITHOUT awaiting termination — so a fire wedged in a
            // non-interruptible call leaks a single (cheap) virtual thread rather than
            // freezing the poll loop. The claim's lease expiry lets another instance
            // reclaim the schedule. Never awaitTermination() here.
            executor.shutdownNow();
        }
    }

    /**
     * Fire an already-claimed schedule and record its outcome. Must not throw —
     * error isolation for the concurrent dispatch depends on this.
     */
    private void fireClaimedSchedule(ScheduleConfiguration schedule) {
        boolean wasInterrupted = false;
        try {
            // Fix #4: compute correct attempt number from schedule state
            int attemptNumber = schedule.getFailCount() + 1;

            // Fire the schedule
            fireCounter.increment();
            ScheduleFireLog fireLog = fireDurationTimer.record(() -> fireExecutor.fire(schedule, instanceId, attemptNumber));

            // fire() deliberately re-asserts the interrupt flag before returning, so the
            // signal is not lost. Park it for the duration of the bookkeeping below and
            // restore it in the finally: markFailed()/markCompleted() are Mongo writes, and
            // the sync driver throws MongoInterruptedException on connection checkout while
            // the flag is set. onFireFailed() swallows that, so failCount would never
            // increment — leaving the schedule CLAIMED with nextFire in the past,
            // re-claimed
            // on every lease expiry, and unable to ever reach maxRetries or dead-letter.
            // An interrupt must not turn a failing schedule into an unbounded re-fire loop.
            wasInterrupted = Thread.interrupted();

            // Handle result
            if (FireStatus.COMPLETED.name().equals(fireLog.status())) {
                onFireCompleted(schedule);
            } else {
                onFireFailed(schedule);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Error processing schedule %s", schedule.getId());
            // Same reasoning as above: the bookkeeping write must not run under a set flag.
            wasInterrupted |= Thread.interrupted();
            try {
                onFireFailed(schedule);
            } catch (Exception nested) {
                LOGGER.errorf(nested, "[SCHEDULE] Could not mark schedule %s as failed", schedule.getId());
            }
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void onFireCompleted(ScheduleConfiguration schedule) {
        try {
            Instant nextFire = computeNextFire(schedule);
            scheduleStore.markCompleted(schedule.getId(), nextFire);
            // Note: markCompleted with null nextFire auto-disables (fix #5 in
            // MongoScheduleStore)
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to mark completed: %s", schedule.getId());
        }
    }

    /**
     * Compute next fire time based on trigger type. Fix #13: handle heartbeat and
     * one-shot correctly.
     */
    private Instant computeNextFire(ScheduleConfiguration schedule) {
        TriggerType type = schedule.getTriggerType();
        if (type == null) {
            type = TriggerType.CRON; // backward compat
        }

        return switch (type) {
            case CRON -> {
                if (schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank()) {
                    ZoneId zoneId = resolveTimeZone(schedule.getTimeZone());
                    yield CronParser.computeNextFire(schedule.getCronExpression(), Instant.now(), zoneId);
                }
                // Deliberately NO oneTimeAt branch: null is the signal markCompleted uses
                // to disable a finished one-shot. Re-arming it here would fire it again
                // immediately, forever.
                // One-shot CRON with no expression → done
                yield null;
            }
            case HEARTBEAT -> {
                // Drift-proof: anchor the next fire on the time this fire was DUE, not on
                // the moment the turn happened to finish. Adding the interval to
                // Instant.now() after the fact made every heartbeat drift by the duration
                // of each fire — a 40s turn on a 60s heartbeat actually fired every ~100s
                // — contradicting the documented contract on both this class and
                // TriggerType.HEARTBEAT.
                Long intervalSec = schedule.getHeartbeatIntervalSeconds();
                if (intervalSec != null && intervalSec > 0) {
                    Instant now = Instant.now();
                    Instant due = schedule.getNextFire();
                    Instant anchored = due != null ? due.plusSeconds(intervalSec) : now.plusSeconds(intervalSec);
                    // Clamp: a fire that overran a WHOLE interval would otherwise be
                    // scheduled into the past, which is a tight re-fire loop rather than
                    // catching up. Only then does the clamp engage — a fire that ran late
                    // but inside the interval keeps its cadence.
                    yield anchored.isBefore(now) ? now.plusSeconds(intervalSec) : anchored;
                }
                // Fallback: try cron expression if set
                if (schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank()) {
                    ZoneId zoneId = resolveTimeZone(schedule.getTimeZone());
                    yield CronParser.computeNextFire(schedule.getCronExpression(), Instant.now(), zoneId);
                }
                yield null;
            }
        };
    }

    private void onFireFailed(ScheduleConfiguration schedule) {
        try {
            int newFailCount = schedule.getFailCount() + 1;
            if (newFailCount >= maxRetries) {
                // Dead-letter
                scheduleStore.markDeadLettered(schedule.getId());
                deadLetterCounter.increment();
                LOGGER.warnf("[SCHEDULE] Schedule '%s' (id=%s) dead-lettered after %d retries", schedule.getName(), schedule.getId(), newFailCount);
            } else {
                // Exponential backoff
                long delaySec = (long) (backoffBaseSeconds * Math.pow(backoffMultiplier, newFailCount - 1));
                Instant nextRetry = Instant.now().plusSeconds(delaySec);
                scheduleStore.markFailed(schedule.getId(), nextRetry);
                fireFailedCounter.increment();
                LOGGER.warnf("[SCHEDULE] Schedule '%s' (id=%s) failed (attempt %d/%d), retry at %s", schedule.getName(), schedule.getId(),
                        newFailCount, maxRetries, nextRetry);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Error handling failure for schedule %s", schedule.getId());
        }
    }

    private ZoneId resolveTimeZone(String timeZone) {
        if (timeZone != null && !timeZone.isBlank()) {
            try {
                return ZoneId.of(timeZone);
            } catch (Exception e) {
                LOGGER.warnf("Invalid time zone '%s', falling back to %s", timeZone, defaultTimeZone);
            }
        }
        return ZoneId.of(defaultTimeZone);
    }

    // --- Manual (REST-initiated) fires ---

    /**
     * Claim a schedule on behalf of a manual {@code POST /schedules/{id}/fire}, on
     * exactly the terms the poller itself claims on.
     * <p>
     * The lease expiry is the whole point of routing this through here. Passing
     * {@code now} as the expiry would match the CAS clause that steals a
     * <em>crashed</em> instance's claim ({@code claimedAt <= leaseExpiry}) and so
     * would seize the claim of a fire that is still running — the opposite of what
     * claiming is for. {@code now - leaseTimeout} steals only a genuinely stale
     * claim.
     *
     * @return {@code true} when this call now owns the schedule; {@code false} when
     *         the poller or another operator is already firing it
     */
    public boolean claimForManualFire(ScheduleConfiguration schedule) {
        Instant now = Instant.now();
        return claimSchedule(schedule, now, now.minus(leaseTimeout));
    }

    /**
     * Record the outcome of a manual fire and release its claim, through the very
     * same state machine a polled fire goes through — so retry backoff,
     * dead-lettering and one-shot disabling behave identically no matter who
     * triggered the fire. Never throws: the fire already happened, and the caller's
     * HTTP response must not turn into a 500 because a bookkeeping write failed.
     * <p>
     * Sharing the state machine has four consequences a manual fire inherits whole,
     * and they are deliberate rather than accidental:
     * <ul>
     * <li>A successful fire re-arms the schedule. For HEARTBEAT that means
     * {@link #computeNextFire} anchors on {@code nextFire} (the drift-proof rule),
     * so firing manually while the next fire is still in the future moves it out by
     * one further interval — the cadence is not additionally advanced by the manual
     * turn, but the fire the operator pre-empted is consumed.</li>
     * <li>{@code markCompleted} clears {@code failCount} and rewrites
     * {@code lastFired}: a manual fire that succeeds is a genuine successful fire,
     * so a schedule that had been failing stops being in backoff.</li>
     * <li>A failed manual fire increments {@code failCount} and can therefore
     * dead-letter the schedule, exactly as a polled failure would.</li>
     * <li>A successful manual fire CONSUMES a one-shot. {@link #computeNextFire}
     * has no {@code oneTimeAt} branch — by design, since {@code null} is what tells
     * {@code markCompleted} a one-shot is finished — so the schedule is disabled
     * with {@code nextFire} cleared, just as a polled fire of it would. "Fire now"
     * on a pending one-shot is therefore not a rehearsal: it is the run. An
     * operator who wants it back must re-arm it through {@code POST
     * /schedules/{id}/enable}, which does handle {@code oneTimeAt}.</li>
     * </ul>
     */
    public void recordManualFireOutcome(ScheduleConfiguration schedule, ScheduleFireLog fireLog) {
        try {
            if (fireLog != null && FireStatus.COMPLETED.name().equals(fireLog.status())) {
                onFireCompleted(schedule);
            } else {
                onFireFailed(schedule);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Could not record the outcome of a manual fire of schedule %s", schedule.getId());
        }
    }

    // --- Accessors for admin/status ---

    public String getInstanceId() {
        return instanceId;
    }

    public boolean isEnabled() {
        return schedulingEnabled;
    }
}
