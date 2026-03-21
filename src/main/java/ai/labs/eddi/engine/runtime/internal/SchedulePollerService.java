package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.schedule.IScheduleStore;
import ai.labs.eddi.configs.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.configs.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.configs.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.configs.schedule.model.ScheduleFireLog;
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
import java.util.List;

/**
 * Polls the schedule store for due schedules and fires them.
 * <p>
 * Uses atomic CAS claiming to ensure exactly-once execution across
 * clustered EDDI instances. Implements exponential backoff on failure
 * with dead-lettering after max retries.
 * <p>
 * Supports two trigger types:
 * <ul>
 *   <li>{@code CRON} — wall-clock aligned via cron expression</li>
 *   <li>{@code HEARTBEAT} — interval-based, drift-proof (nextFire = lastFired + interval)</li>
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
    private final String configuredInstanceId;
    private final String defaultTimeZone;
    private final long minIntervalSeconds;

    private String instanceId;
    private Counter pollCounter;
    private Counter fireCounter;
    private Counter fireFailedCounter;
    private Counter claimConflictCounter;
    private Counter deadLetterCounter;
    private Timer fireDurationTimer;

    @Inject
    public SchedulePollerService(
            IScheduleStore scheduleStore,
            ScheduleFireExecutor fireExecutor,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.schedule.enabled", defaultValue = "true")
            boolean schedulingEnabled,
            @ConfigProperty(name = "eddi.schedule.lease-timeout", defaultValue = "5m")
            Duration leaseTimeout,
            @ConfigProperty(name = "eddi.schedule.max-retries", defaultValue = "5")
            int maxRetries,
            @ConfigProperty(name = "eddi.schedule.backoff-base-seconds", defaultValue = "15")
            int backoffBaseSeconds,
            @ConfigProperty(name = "eddi.schedule.backoff-multiplier", defaultValue = "4")
            int backoffMultiplier,
            @ConfigProperty(name = "eddi.schedule.instance-id", defaultValue = "")
            String configuredInstanceId,
            @ConfigProperty(name = "eddi.schedule.default-timezone", defaultValue = "UTC")
            String defaultTimeZone,
            @ConfigProperty(name = "eddi.schedule.min-interval-seconds", defaultValue = "60")
            long minIntervalSeconds) {
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
        this.minIntervalSeconds = minIntervalSeconds;
    }

    @PostConstruct
    void init() {
        // Resolve instance ID
        if (configuredInstanceId != null && !configuredInstanceId.isBlank()) {
            instanceId = configuredInstanceId;
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
        fireDurationTimer = meterRegistry.timer("eddi.schedule.fire.duration");

        if (schedulingEnabled) {
            LOGGER.infof("Schedule poller initialized (instance=%s, leaseTimeout=%s, maxRetries=%d)",
                    instanceId, leaseTimeout, maxRetries);
        } else {
            LOGGER.info("Schedule poller DISABLED (eddi.schedule.enabled=false)");
        }
    }

    /**
     * Main poll loop — runs at the configured interval.
     * Finds due schedules, claims them atomically, and fires.
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

            for (ScheduleConfiguration schedule : dueSchedules) {
                processSchedule(schedule, now);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Poll cycle failed");
        }
    }

    private void processSchedule(ScheduleConfiguration schedule, Instant now) {
        try {
            // Atomic CAS claim
            boolean claimed = scheduleStore.tryClaim(schedule.getId(), instanceId, now);

            if (!claimed) {
                claimConflictCounter.increment();
                LOGGER.debugf("[SCHEDULE] Claim conflict for schedule %s — another instance got it", schedule.getId());
                return;
            }

            // Fix #4: compute correct attempt number from schedule state
            int attemptNumber = schedule.getFailCount() + 1;

            // Fire the schedule
            fireCounter.increment();
            ScheduleFireLog fireLog = fireDurationTimer.record(() ->
                    fireExecutor.fire(schedule, instanceId, attemptNumber)
            );

            // Handle result
            if (FireStatus.COMPLETED.name().equals(fireLog.status())) {
                onFireCompleted(schedule);
            } else {
                onFireFailed(schedule);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Error processing schedule %s", schedule.getId());
            try {
                onFireFailed(schedule);
            } catch (Exception nested) {
                LOGGER.errorf(nested, "[SCHEDULE] Could not mark schedule %s as failed", schedule.getId());
            }
        }
    }

    private void onFireCompleted(ScheduleConfiguration schedule) {
        try {
            Instant nextFire = computeNextFire(schedule);
            scheduleStore.markCompleted(schedule.getId(), nextFire);
            // Note: markCompleted with null nextFire auto-disables (fix #5 in MongoScheduleStore)
        } catch (Exception e) {
            LOGGER.errorf(e, "[SCHEDULE] Failed to mark completed: %s", schedule.getId());
        }
    }

    /**
     * Compute next fire time based on trigger type.
     * Fix #13: handle heartbeat and one-shot correctly.
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
                // One-shot CRON with no expression → done
                yield null;
            }
            case HEARTBEAT -> {
                // Interval-based: nextFire = now + interval (drift-proof from last actual fire)
                Long intervalSec = schedule.getHeartbeatIntervalSeconds();
                if (intervalSec != null && intervalSec > 0) {
                    yield Instant.now().plusSeconds(intervalSec);
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
                LOGGER.warnf("[SCHEDULE] Schedule '%s' (id=%s) dead-lettered after %d retries",
                        schedule.getName(), schedule.getId(), newFailCount);
            } else {
                // Exponential backoff
                long delaySec = (long) (backoffBaseSeconds * Math.pow(backoffMultiplier, newFailCount - 1));
                Instant nextRetry = Instant.now().plusSeconds(delaySec);
                scheduleStore.markFailed(schedule.getId(), nextRetry);
                fireFailedCounter.increment();
                LOGGER.warnf("[SCHEDULE] Schedule '%s' (id=%s) failed (attempt %d/%d), retry at %s",
                        schedule.getName(), schedule.getId(), newFailCount, maxRetries, nextRetry);
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

    // --- Accessors for admin/status ---

    public String getInstanceId() {
        return instanceId;
    }

    public boolean isEnabled() {
        return schedulingEnabled;
    }
}
