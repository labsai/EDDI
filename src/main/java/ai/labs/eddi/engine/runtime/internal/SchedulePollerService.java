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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Most fires this instance runs at once. The poll claims only as many due
     * schedules as there are free slots, so a burst never starts more work than
     * this, and the rest stay due for the next cycle (or another instance).
     */
    @ConfigProperty(name = "eddi.schedule.max-concurrent-fires", defaultValue = "100")
    int maxConcurrentFires = 100;

    /**
     * On shutdown: how long in-flight fires are given to finish before they are
     * interrupted, and then how long the interrupted ones are given to record their
     * outcome.
     */
    Duration shutdownDrain = Duration.ofSeconds(5);

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

    // Fire execution, decoupled from the claim cycle. Created in init().
    private Semaphore fireSlots;
    private ExecutorService fireWorkers;
    private ScheduledExecutorService fireWatchdog;
    private final Set<InFlightFire> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

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

        fireSlots = new Semaphore(Math.max(1, maxConcurrentFires));
        fireWorkers = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "schedule-fire-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.setRemoveOnCancelPolicy(true);
        fireWatchdog = watchdog;

        if (schedulingEnabled) {
            LOGGER.infof("Schedule poller initialized (instance=%s, leaseTimeout=%s, fireTimeout=%s, maxRetries=%d, maxConcurrentFires=%d)",
                    instanceId, effectiveLeaseTimeout, fireTimeout, maxRetries, Math.max(1, maxConcurrentFires));
        } else {
            LOGGER.info("Schedule poller DISABLED (eddi.schedule.enabled=false)");
        }
    }

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

    /**
     * Main poll loop — runs at the configured interval. Finds due schedules, claims
     * them atomically, hands each claimed schedule to the fire executor and
     * returns.
     * <p>
     * This is the short <em>claim cycle</em> and nothing else: it never waits for a
     * fire, so {@code SKIP} only skips a claim cycle that overlaps another claim
     * cycle. It used to wait up to the fire timeout for its batch, so one fire that
     * ran for minutes suppressed every poll on the instance, and a schedule that
     * fell due meanwhile (a HITL approval deadline included) waited for the long
     * fire instead of being claimed near its due time. Fires now run on their own
     * executor (see {@link #dispatch}), at most
     * {@code eddi.schedule.max-concurrent-fires} at once, and a poll claims no more
     * schedules than there are free slots.
     */
    @Scheduled(every = "${eddi.schedule.poll-interval:15s}", identity = "schedule-poller",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollDueSchedules() {
        if (!schedulingEnabled || stopping) {
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
            // thread so exactly one instance owns each schedule. A fire slot is taken
            // first, so nothing is claimed that this instance has no room to run (its
            // lease would tick away unfired); the rest stay due for the next cycle or
            // another instance. Claimed schedules fire concurrently on virtual threads
            // with per-fire error isolation, so a burst of one-shot HITL timeouts does
            // not serialize behind one thread.
            for (ScheduleConfiguration schedule : dueSchedules) {
                // Shutdown can begin while this loop runs: a claim made after it is rejected at
                // dispatch and then sits CLAIMED until its lease expires, so no replica can run
                // it.
                if (stopping) {
                    break;
                }
                if (!fireSlots.tryAcquire()) {
                    LOGGER.debugf("[SCHEDULE] All %d fire slots busy — leaving the remaining due schedules for the next cycle",
                            Math.max(1, maxConcurrentFires));
                    break;
                }
                boolean handedOver = false;
                try {
                    // Read BEFORE the claim overwrites it: a due row still CLAIMED is one
                    // whose lease expired with no outcome recorded.
                    boolean staleClaim = schedule.getFireStatus() == FireStatus.CLAIMED;
                    if (claimSchedule(schedule, now, leaseExpiry)) {
                        if (staleClaim) {
                            onUnreportedFire(schedule);
                        } else {
                            handedOver = dispatch(schedule);
                        }
                    }
                } finally {
                    if (!handedOver) {
                        fireSlots.release();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Poll cycle failed");
        }
    }

    /**
     * A claim whose lease ran out without an outcome: the fire that held it never
     * reported — its node died (an out-of-memory kill, say) or it wedged in a call
     * that ignored the interrupt. Re-claiming used to re-fire it at once with
     * {@code failCount} untouched, so a fire that kills its node killed the next
     * one too, forever, and never dead-lettered. It counts as the failed attempt it
     * was instead: retried with backoff, dead-lettered after {@code max-retries}.
     */
    private void onUnreportedFire(ScheduleConfiguration schedule) {
        // No fireFailedCounter.increment() here: onFireFailed counts the attempt (or
        // the dead letter), exactly as for a fire that reported its failure.
        LOGGER.warnf("[SCHEDULE] Schedule '%s' (id=%s) was still claimed when its lease expired: the previous fire never reported "
                + "an outcome (its node stopped, or the fire wedged). Counting it as a failed attempt.", schedule.getName(),
                schedule.getId());
        onFireFailed(schedule);
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
     * One fire handed to the executor: it releases its slot and its bookkeeping
     * exactly once, whether it ended normally or was cut off by the watchdog.
     */
    private final class InFlightFire {
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile ScheduledFuture<?> deadline;

        void finish() {
            if (finished.compareAndSet(false, true)) {
                ScheduledFuture<?> watch = deadline;
                if (watch != null) {
                    watch.cancel(false);
                }
                inFlight.remove(this);
                fireSlots.release();
            }
        }
    }

    /**
     * Run one claimed schedule on the fire executor and return at once. The caller
     * holds a fire slot; on success this method owns it and releases it when the
     * fire ends.
     * <p>
     * Each fire is bounded on its own by the fire timeout plus the grace: a
     * watchdog cancels it at the deadline, which interrupts it best-effort. A fire
     * that ignores the interrupt keeps its (cheap) virtual thread but gives its
     * slot back, and its claim expires with the lease. Deliberately never awaited —
     * waiting on a non-interruptible call is the hang the timeout exists to
     * prevent.
     *
     * @return {@code false} when the executor refused it (shutting down); the
     *         caller keeps the slot and the claim simply expires with its lease
     */
    private boolean dispatch(ScheduleConfiguration schedule) {
        InFlightFire fire = new InFlightFire();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                fireClaimedSchedule(schedule);
            } finally {
                fire.finish();
            }
        }, null);
        inFlight.add(fire);
        try {
            Duration deadline = batchDeadline();
            fire.deadline = fireWatchdog.schedule(() -> {
                if (!task.isDone()) {
                    task.cancel(true);
                    fireTimedOutCounter.increment();
                    LOGGER.errorf("[SCHEDULE] A fire exceeded eddi.schedule.fire-timeout (%s, plus %s grace) and was interrupted. "
                            + "If it honours the interrupt it is recorded FAILED and retried with backoff; if it ignores it, nothing "
                            + "is recorded until its %s lease expires, and the re-claim then counts it as a failed attempt. Raise "
                            + "eddi.schedule.fire-timeout for jobs that legitimately run longer (the lease follows it).", fireTimeout,
                            batchGrace, effectiveLeaseTimeout);
                    fire.finish();
                }
            }, Math.max(deadline.toNanos(), 1_000_000L), TimeUnit.NANOSECONDS);
            fireWorkers.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.remove(fire);
            if (fire.deadline != null) {
                fire.deadline.cancel(false);
            }
            LOGGER.warnf("[SCHEDULE] Fire of schedule %s not started: the executor is shutting down; its claim expires with the lease",
                    schedule.getId());
            return false;
        }
    }

    /** Number of fires currently in flight — for tests and diagnostics. */
    int inFlightFires() {
        return inFlight.size();
    }

    /** Waits until no fire is in flight. Test seam: production never waits. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (!inFlight.isEmpty()) {
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(2);
        }
        return true;
    }

    /** One claim cycle, then wait for the fires it started. Test seam. */
    void pollAndAwait() {
        pollDueSchedules();
        try {
            awaitIdle(batchDeadline().plusSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Graceful shutdown: stop claiming, give in-flight fires {@link #shutdownDrain}
     * to finish, then interrupt the rest and give them the same to record their
     * outcome. A fire that still has not reported is left to its claim's lease,
     * which counts it as a failed attempt.
     */
    @PreDestroy
    void shutdownFires() {
        stopping = true;
        if (fireWorkers == null) {
            return;
        }
        fireWorkers.shutdown();
        try {
            if (!fireWorkers.awaitTermination(shutdownDrain.toMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.warnf("[SCHEDULE] %d fire(s) still running after %s — interrupting", inFlight.size(), shutdownDrain);
                fireWorkers.shutdownNow();
                fireWorkers.awaitTermination(shutdownDrain.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            fireWorkers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            fireWatchdog.shutdownNow();
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
     * The bound a manual fire runs under — the same one a polled batch has
     * ({@link #batchDeadline}). A manual fire used to run unbounded on the request
     * thread: a Dream or ingestion run that outlasted the claim's lease had its
     * schedule re-claimed by the poller and started a second time alongside it. The
     * lease is kept strictly longer than this bound (see {@link #init}).
     */
    public Duration manualFireDeadline() {
        return batchDeadline();
    }

    /**
     * Runs {@code fire} on a virtual thread and waits at most {@code deadline} for
     * it (the default fire timeout plus grace when {@code null}); a fire still
     * running then is interrupted and abandoned, exactly as in {@link #dispatch}.
     * Otherwise the fire behaves as if it had run on the caller's thread: what it
     * throws is rethrown, and an interrupt it re-asserted on the way out is
     * re-asserted on the caller.
     * <p>
     * Only an interrupt the fire itself ended with is carried over. The interrupt
     * that {@code cancel(true)} delivers to a fire cut off at the deadline belongs
     * to the abandoned worker; carrying it over (it can land after the timeout,
     * whenever the worker unwinds) interrupted the caller — the request thread —
     * for nothing. A caller interrupted while waiting cancels the fire and keeps
     * its own interrupt.
     *
     * @param deadline
     *            the bound; {@code null} or not positive means the default
     * @return the fire log
     * @throws FireAbandonedException
     *             when the fire was cut off at the deadline or the caller was
     *             interrupted. The worker was only asked to stop: one that ignores
     *             the interrupt is still acting, so the caller must NOT release the
     *             schedule's claim — it stays {@code CLAIMED} until the lease
     *             expires, and the poller then counts it as the failed attempt it
     *             was (see {@link #onUnreportedFire}) instead of starting another
     *             alongside it.
     */
    public static ScheduleFireLog runWithinDeadline(Callable<ScheduleFireLog> fire, Duration deadline) throws Exception {
        Duration bound = deadline != null && deadline.isPositive() ? deadline : Duration.ofMinutes(5).plusSeconds(30);
        AtomicBoolean endedInterrupted = new AtomicBoolean();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<ScheduleFireLog> future = executor.submit(() -> {
                try {
                    return fire.call();
                } finally {
                    endedInterrupted.set(Thread.interrupted());
                }
            });
            try {
                ScheduleFireLog log = future.get(Math.max(bound.toMillis(), 1L), TimeUnit.MILLISECONDS);
                restoreInterrupt(endedInterrupted.get());
                return log;
            } catch (TimeoutException e) {
                future.cancel(true);
                LOGGER.errorf("[SCHEDULE] A manual fire exceeded its %s bound (eddi.schedule.fire-timeout plus grace) and was interrupted",
                        bound);
                throw new FireAbandonedException("The fire exceeded its " + bound + " bound and was interrupted", true);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new FireAbandonedException("The caller stopped waiting for the fire", false);
            } catch (ExecutionException e) {
                // The worker's finally ran before the future completed exceptionally.
                restoreInterrupt(endedInterrupted.get());
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        } finally {
            // As in dispatch: never await a fire wedged in a non-interruptible call.
            executor.shutdownNow();
        }
    }

    /**
     * A manual fire that was cut off (deadline) or whose caller stopped waiting.
     * The fire may still be running, so its claim is left to expire rather than
     * released.
     */
    public static class FireAbandonedException extends Exception {
        private final boolean timedOut;

        FireAbandonedException(String message, boolean timedOut) {
            super(message);
            this.timedOut = timedOut;
        }

        /**
         * True when the deadline passed; false when the waiting caller was interrupted.
         */
        public boolean timedOut() {
            return timedOut;
        }
    }

    private static void restoreInterrupt(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

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
