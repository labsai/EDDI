/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.hitl.HitlSchedules;
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
 * expected to be idempotent — HITL timeout fires carry the id of the pause they
 * were armed for and resolve via a state CAS (resume/cancel), so a duplicate
 * finds nothing left to decide. Implements exponential backoff on failure with
 * dead-lettering after max retries — except for HITL approval timeouts, which
 * are re-armed at {@code eddi.schedule.hitl-timeout-retry-interval} and never
 * dead-lettered (see {@link #onFireFailed}).
 * <p>
 * <b>Lease versus fire timeout.</b> A running fire is bounded by
 * {@code eddi.schedule.fire-timeout} (plus a short grace for its bookkeeping);
 * the claim's lease must outlast that, or another poll — this instance's next
 * one included — reclaims a schedule whose fire is still running and starts a
 * second copy. {@link #init} enforces {@code lease-timeout > fire-timeout +
 * grace} and raises the effective lease, with a warning, when the configuration
 * says otherwise.
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

    /**
     * How long one fire may run before the batch stops waiting for it and cancels
     * it. The same property {@link ScheduleFireExecutor} bounds a conversation turn
     * with, so the two cannot disagree.
     */
    @ConfigProperty(name = "eddi.schedule.fire-timeout", defaultValue = "5m")
    Duration fireTimeout = Duration.ofMinutes(5);

    /**
     * Time a fire is given past {@link #fireTimeout} to record its own outcome
     * (fire log, state write) before the batch cancels it.
     */
    Duration batchGrace = Duration.ofSeconds(30);

    /**
     * When a HITL approval timeout's decision could not be applied yet (store blip,
     * node draining, a decision in flight), how long until it is tried again.
     */
    @ConfigProperty(name = "eddi.schedule.hitl-timeout-retry-interval", defaultValue = "2m")
    Duration hitlTimeoutRetryInterval = Duration.ofMinutes(2);

    /** Margin the effective lease keeps above {@code fireTimeout + batchGrace}. */
    static final Duration LEASE_MARGIN = Duration.ofMinutes(1);

    /**
     * The lease actually used: the configured one, raised in {@link #init} when it
     * would expire while a fire can still legitimately be running.
     */
    private Duration effectiveLeaseTimeout;

    private String instanceId;
    private Counter pollCounter;
    private Counter fireCounter;
    private Counter fireFailedCounter;
    private Counter fireSkippedCounter;
    private Counter claimConflictCounter;
    private Counter deadLetterCounter;
    private Counter fireLogsPrunedCounter;
    private Timer fireDurationTimer;
    private Counter fireTimedOutCounter;

    @Inject
    public SchedulePollerService(IScheduleStore scheduleStore, ScheduleFireExecutor fireExecutor, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.schedule.enabled", defaultValue = "true") boolean schedulingEnabled,
            @ConfigProperty(name = "eddi.schedule.lease-timeout", defaultValue = "10m") Duration leaseTimeout,
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
        this.effectiveLeaseTimeout = leaseTimeout;
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
        fireSkippedCounter = meterRegistry.counter("eddi.schedule.fire.skipped");
        claimConflictCounter = meterRegistry.counter("eddi.schedule.claim.conflict");
        deadLetterCounter = meterRegistry.counter("eddi.schedule.fire.deadlettered");
        fireLogsPrunedCounter = meterRegistry.counter("eddi.schedule.firelog.pruned");
        fireDurationTimer = meterRegistry.timer("eddi.schedule.fire.duration");
        fireTimedOutCounter = meterRegistry.counter("eddi.schedule.fire.timedout");

        effectiveLeaseTimeout = effectiveLease(leaseTimeout, batchDeadline());
        if (!effectiveLeaseTimeout.equals(leaseTimeout)) {
            LOGGER.warnf("eddi.schedule.lease-timeout (%s) does not outlast eddi.schedule.fire-timeout (%s) plus %s of grace — "
                    + "a schedule would be reclaimable while its fire is still running, and fire twice. "
                    + "Using a lease of %s instead; set eddi.schedule.lease-timeout above the fire timeout to silence this.",
                    leaseTimeout, fireTimeout, batchGrace, effectiveLeaseTimeout);
        }

        if (schedulingEnabled) {
            LOGGER.infof("Schedule poller initialized (instance=%s, leaseTimeout=%s, fireTimeout=%s, maxRetries=%d)", instanceId,
                    effectiveLeaseTimeout, fireTimeout, maxRetries);
        } else {
            LOGGER.info("Schedule poller DISABLED (eddi.schedule.enabled=false)");
        }
    }

    /**
     * Main poll loop — runs at the configured interval. Finds due schedules, claims
     * them atomically, and fires.
     */
    /** How long a batch waits for its fires: the fire timeout plus the grace. */
    Duration batchDeadline() {
        Duration timeout = fireTimeout != null ? fireTimeout : Duration.ofMinutes(5);
        Duration grace = batchGrace != null ? batchGrace : Duration.ZERO;
        return timeout.plus(grace);
    }

    /**
     * The lease to use: the configured one if it outlasts {@code batchDeadline},
     * otherwise {@code batchDeadline + LEASE_MARGIN}.
     */
    static Duration effectiveLease(Duration configuredLease, Duration batchDeadline) {
        if (configuredLease != null && configuredLease.compareTo(batchDeadline) > 0) {
            return configuredLease;
        }
        return batchDeadline.plus(LEASE_MARGIN);
    }

    /** The lease in force — exposed for tests and the status endpoint. */
    public Duration getEffectiveLeaseTimeout() {
        return effectiveLeaseTimeout;
    }

    @Scheduled(every = "${eddi.schedule.poll-interval:15s}", identity = "schedule-poller")
    void pollDueSchedules() {
        if (!schedulingEnabled) {
            return;
        }

        pollCounter.increment();

        try {
            Instant now = Instant.now();
            Instant leaseExpiry = now.minus(effectiveLeaseTimeout);

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
     * The poller's claim: returns true only if the CAS claim succeeded, and never
     * throws — one unclaimable schedule must not abort the rest of the batch.
     * <p>
     * The conflict metric and the "another instance got it" reading belong HERE and
     * not in {@link #tryClaimFor}, because they are only true of the poll path. A
     * manual fire is refused for states the poller never even fetches
     * (dead-lettered, or FAILED still inside its backoff), and counting those as
     * cluster contention made {@code eddi.schedule.claim.conflict} — documented as
     * "instances racing for the same schedule" — react to an operator pressing a
     * button.
     */
    private boolean claimSchedule(ScheduleConfiguration schedule, Instant now, Instant leaseExpiry) {
        try {
            boolean claimed = tryClaimFor(schedule, now, leaseExpiry);
            if (!claimed) {
                claimConflictCounter.increment();
                LOGGER.debugf("[SCHEDULE] Claim conflict for schedule %s — another instance got it", schedule.getId());
            }
            return claimed;
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Error claiming schedule %s", schedule.getId());
            return false;
        }
    }

    /**
     * The CAS claim itself, with the store's failure left to the caller to
     * interpret. A store error is NOT a claim conflict: reporting it as one told a
     * manual caller "already being fired" during a database blip, which is a
     * statement about the cluster that nobody had checked.
     */
    private boolean tryClaimFor(ScheduleConfiguration schedule, Instant now, Instant leaseExpiry)
            throws IResourceStore.ResourceStoreException {
        if (!scheduleStore.tryClaim(schedule.getId(), instanceId, now, leaseExpiry)) {
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
            // Bound the WHOLE batch by ONE shared deadline, not a timeout per future:
            // a per-future bound in this sequential loop would let N stalled fires pin
            // the poll thread for N times as long (hours for a large batch), defeating
            // the point of the timeout. The deadline is the FIRE timeout plus a grace,
            // and deliberately not the lease: when the two were equal, a fire that ran
            // to the deadline was interrupted at the very moment its claim became
            // stealable, so a long Dream run was both cut short and re-fired. init()
            // keeps the lease strictly longer than this deadline. On timeout, cancel
            // (best-effort interrupt) and move on.
            Duration deadline = batchDeadline();
            long deadlineNanos = System.nanoTime() + Math.max(deadline.toNanos(), 1_000_000L);
            for (Future<?> future : futures) {
                long remainingMs = Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                try {
                    future.get(remainingMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    fireTimedOutCounter.increment();
                    LOGGER.errorf("[SCHEDULE] A fire exceeded eddi.schedule.fire-timeout (%s, plus %s grace) and was interrupted. "
                            + "It is recorded FAILED and retried with backoff; raise eddi.schedule.fire-timeout for jobs that "
                            + "legitimately run longer (the lease follows it). If it ignores the interrupt, its schedule becomes "
                            + "reclaimable once the %s lease expires.", fireTimeout, batchGrace, effectiveLeaseTimeout);
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
            recordFireOutcome(schedule, fireLog);
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

    /**
     * Route one fire log into the schedule's state machine. Shared by the poller
     * and by {@link #recordManualFireOutcome} so a manual fire cannot drift from a
     * polled one.
     * <p>
     * Three outcomes, not two. SKIPPED is the one that is neither: the coordinator
     * dropped the turn without consuming the input (busy or human-paused
     * conversation), so nothing ran — but nothing broke either, and feeding it to
     * {@link #onFireFailed} dead-lettered a healthy heartbeat after
     * {@code max-retries} consecutive skips.
     */
    private void recordFireOutcome(ScheduleConfiguration schedule, ScheduleFireLog fireLog) {
        String status = fireLog != null ? fireLog.status() : null;
        if (FireStatus.COMPLETED.name().equals(status)) {
            onFireCompleted(schedule);
        } else if (FireStatus.SKIPPED.name().equals(status)) {
            onFireSkipped(schedule);
        } else {
            onFireFailed(schedule);
        }
    }

    /**
     * Release the claim of a skipped fire and re-arm the schedule at its next
     * cadence, WITHOUT touching failCount.
     * <p>
     * A due time that has NOT yet arrived is kept as it is rather than rolled
     * forward. Only a polled fire is guaranteed to be overdue; a manual "fire now"
     * claims at any time — {@code tryClaim} deliberately has no
     * {@code nextFire <= now} guard — so an operator firing a daily heartbeat in
     * the morning, into a conversation a human happens to be chatting in, would
     * otherwise push tonight's delivery out by a whole interval. Nothing ran, so
     * nothing was consumed: the still-pending fire IS the next cadence, and
     * advancing past it cancels a scheduled delivery silently. This is where a skip
     * parts company with a success, which does consume the pending fire (see
     * {@link #recordManualFireOutcome}).
     * <p>
     * A schedule with no next cadence — a one-shot whose moment has passed — has
     * nothing to re-arm to, and leaving it PENDING with nextFire in the past is a
     * tight re-fire loop. Those fall through to {@link #onFireFailed}, which
     * retries them with backoff and eventually dead-letters: for a one-shot that is
     * the honest outcome, because the single delivery it existed for really never
     * happened.
     */
    private void onFireSkipped(ScheduleConfiguration schedule) {
        try {
            Instant due = schedule.getNextFire();
            Instant nextFire = due != null && due.isAfter(Instant.now()) ? due : computeNextFire(schedule);
            if (nextFire == null) {
                onFireFailed(schedule);
                return;
            }
            scheduleStore.markSkipped(schedule.getId(), schedule.getFireId(), nextFire);
            fireSkippedCounter.increment();
            LOGGER.infof("[SCHEDULE] Fire of schedule '%s' (id=%s) was skipped (conversation busy or awaiting a human); "
                    + "re-armed for %s without counting a failure", schedule.getName(), schedule.getId(), nextFire);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to re-arm skipped schedule %s", schedule.getId());
        }
    }

    private void onFireCompleted(ScheduleConfiguration schedule) {
        try {
            Instant nextFire = computeNextFire(schedule);
            scheduleStore.markCompleted(schedule.getId(), schedule.getFireId(), nextFire);
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
        if (HitlSchedules.isHitlTimeout(schedule.getMetadata())) {
            rearmHitlTimeout(schedule);
            return;
        }
        try {
            int newFailCount = schedule.getFailCount() + 1;
            if (newFailCount >= maxRetries) {
                // Dead-letter
                scheduleStore.markDeadLettered(schedule.getId(), schedule.getFireId());
                deadLetterCounter.increment();
                LOGGER.warnf("[SCHEDULE] Schedule '%s' (id=%s) dead-lettered after %d retries", schedule.getName(), schedule.getId(), newFailCount);
            } else {
                // Exponential backoff
                long delaySec = (long) (backoffBaseSeconds * Math.pow(backoffMultiplier, newFailCount - 1));
                Instant nextRetry = Instant.now().plusSeconds(delaySec);
                scheduleStore.markFailed(schedule.getId(), schedule.getFireId(), nextRetry);
                fireFailedCounter.increment();
                LOGGER.warnf("[SCHEDULE] Schedule '%s' (id=%s) failed (attempt %d/%d), retry at %s", schedule.getName(), schedule.getId(),
                        newFailCount, maxRetries, nextRetry);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Error handling failure for schedule %s", schedule.getId());
        }
    }

    /**
     * A HITL approval timeout whose decision could not be applied yet is re-armed,
     * not retried toward a dead letter.
     * <p>
     * The ordinary retry ladder (15 s, 1 min, 4 min, 16 min, dead letter) assumes
     * the failure is the schedule's own. Here it almost never is: the cause is
     * transient and outside the schedule (a store blip, a node draining for
     * shutdown, a concurrent decision), and the timeout must still apply once it
     * passes. Dead-lettering it — or, as before, logging the fire COMPLETED and
     * disabling the one-shot — turned a finite AUTO_REJECT/AUTO_APPROVE/ABORT
     * policy into wait-forever until the next restart. So the row goes back to
     * PENDING at a fixed interval, with {@code failCount} untouched; every attempt
     * stays visible as a FAILED fire log with its reason. A timeout whose
     * conversation is gone or already decided completes normally instead (see
     * {@code HitlTimeoutHandler}).
     */
    private void rearmHitlTimeout(ScheduleConfiguration schedule) {
        try {
            Instant retryAt = Instant.now().plus(hitlTimeoutRetryInterval);
            scheduleStore.markSkipped(schedule.getId(), schedule.getFireId(), retryAt);
            fireFailedCounter.increment();
            LOGGER.warnf("[SCHEDULE] HITL approval timeout '%s' (id=%s) could not be applied yet — re-armed for %s",
                    schedule.getName(), schedule.getId(), retryAt);
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Could not re-arm HITL approval timeout %s", schedule.getId());
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
     * <p>
     * A store failure PROPAGATES rather than becoming {@code false}. The caller
     * turns {@code false} into "409 — already being fired", which during a database
     * blip is a claim about the cluster that nothing verified; the exception maps
     * to a 500, which is what actually happened.
     *
     * @return {@code true} when this call now owns the schedule; {@code false} when
     *         the schedule is not in a claimable state — the poller or another
     *         operator is firing it, or it is dead-lettered or still in backoff
     * @throws IResourceStore.ResourceStoreException
     *             if the claim could not be attempted at all
     */
    public boolean claimForManualFire(ScheduleConfiguration schedule) throws IResourceStore.ResourceStoreException {
        Instant now = Instant.now();
        return tryClaimFor(schedule, now, now.minus(effectiveLeaseTimeout));
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
     * <li>A SKIPPED manual fire — the operator pressed "fire now" while the
     * conversation was busy or awaiting a human — releases the claim without
     * counting a failure, exactly as a polled skip does, and is the one outcome
     * that does NOT consume a pending fire: nothing ran, so a due time still in the
     * future is left untouched (see {@link #onFireSkipped}).</li>
     * </ul>
     */
    public void recordManualFireOutcome(ScheduleConfiguration schedule, ScheduleFireLog fireLog) {
        try {
            recordFireOutcome(schedule, fireLog);
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
