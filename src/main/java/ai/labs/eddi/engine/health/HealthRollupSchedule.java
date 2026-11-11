/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Makes sure the {@value HealthSchedules#SCHEDULE_NAME} system schedule exists,
 * at startup. Two nodes starting together may both create it; a duplicate only
 * runs the same idempotent rollup twice (each day is recomputed and replaced,
 * and its dirty entry cleared only by token).
 * <p>
 * Created whether or not {@code eddi.health.enabled} is set: with collection
 * off nothing is marked dirty, so the job has nothing to do, and switching
 * collection back on needs no restart of the job.
 */
@ApplicationScoped
public class HealthRollupSchedule {

    private static final Logger LOGGER = Logger.getLogger(HealthRollupSchedule.class);
    static final int SCAN_PAGE = 200;
    static final int SCAN_LIMIT = 10_000;

    private final IScheduleStore scheduleStore;
    private final HealthSettings settings;

    @Inject
    public HealthRollupSchedule(IScheduleStore scheduleStore, HealthSettings settings) {
        this.scheduleStore = scheduleStore;
        this.settings = settings;
    }

    void onStart(@Observes StartupEvent event) {
        ensure();
    }

    boolean installed() throws Exception {
        Optional<ScheduleConfiguration> byName = scheduleStore.readScheduleByName(HealthSchedules.SCHEDULE_NAME);
        if (byName.isPresent() && HealthSchedules.isRollup(byName.get().getMetadata())) {
            return true;
        }
        for (int offset = 0; offset < SCAN_LIMIT; offset += SCAN_PAGE) {
            List<ScheduleConfiguration> page = scheduleStore.readAllSchedules(SCAN_PAGE, offset, true);
            if (page.stream().anyMatch(s -> HealthSchedules.isRollup(s.getMetadata()))) {
                return true;
            }
            if (page.size() < SCAN_PAGE) {
                return false;
            }
        }
        return false;
    }

    /** Creates the schedule when missing; never throws. */
    public void ensure() {
        try {
            if (installed()) {
                return;
            }
            long seconds = settings.rollupInterval().toSeconds();
            ScheduleConfiguration schedule = new ScheduleConfiguration();
            schedule.setName(HealthSchedules.SCHEDULE_NAME);
            schedule.setTriggerType(ScheduleConfiguration.TriggerType.HEARTBEAT);
            schedule.setHeartbeatIntervalSeconds(seconds);
            schedule.setEnabled(true);
            schedule.setNextFire(Instant.now().plusSeconds(Math.min(seconds, 60)));
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(HealthSchedules.rollupMetadata());
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Created the %s system schedule (every %ds)", HealthSchedules.SCHEDULE_NAME, seconds);
        } catch (Exception e) {
            LOGGER.warnf("Could not ensure the %s schedule — Agent Health daily rows wait until it exists: %s", HealthSchedules.SCHEDULE_NAME,
                    e.getMessage());
        }
    }
}
