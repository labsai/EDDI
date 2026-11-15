/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
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
 * Makes sure the {@value HitlAutonomySchedules#SCHEDULE_NAME} system schedule
 * exists, at startup. Two nodes starting together may both create it; a
 * duplicate only runs the same idempotent, compare-and-set maintenance twice.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class HitlAutonomyMaintenanceSchedule {

    private static final Logger LOGGER = Logger.getLogger(HitlAutonomyMaintenanceSchedule.class);
    static final int SCAN_PAGE = 200;
    static final int SCAN_LIMIT = 10_000;

    private final IScheduleStore scheduleStore;
    private final OversightSettings settings;

    @Inject
    public HitlAutonomyMaintenanceSchedule(IScheduleStore scheduleStore, OversightSettings settings) {
        this.scheduleStore = scheduleStore;
        this.settings = settings;
    }

    void onStart(@Observes StartupEvent event) {
        ensure();
    }

    boolean installed() throws Exception {
        Optional<ScheduleConfiguration> byName = scheduleStore.readScheduleByName(HitlAutonomySchedules.SCHEDULE_NAME);
        if (byName.isPresent() && HitlAutonomySchedules.isMaintenance(byName.get().getMetadata())) {
            return true;
        }
        for (int offset = 0; offset < SCAN_LIMIT; offset += SCAN_PAGE) {
            List<ScheduleConfiguration> page = scheduleStore.readAllSchedules(SCAN_PAGE, offset, true);
            if (page.stream().anyMatch(s -> HitlAutonomySchedules.isMaintenance(s.getMetadata()))) {
                return true;
            }
            if (page.size() < SCAN_PAGE) {
                return false;
            }
        }
        return false;
    }

    public void ensure() {
        try {
            if (installed()) {
                return;
            }
            long seconds = Math.max(60, settings.maintenanceInterval().toSeconds());
            ScheduleConfiguration schedule = new ScheduleConfiguration();
            schedule.setName(HitlAutonomySchedules.SCHEDULE_NAME);
            schedule.setTriggerType(ScheduleConfiguration.TriggerType.HEARTBEAT);
            schedule.setHeartbeatIntervalSeconds(seconds);
            schedule.setEnabled(true);
            schedule.setNextFire(Instant.now().plusSeconds(Math.min(seconds, 300)));
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(HitlAutonomySchedules.maintenanceMetadata());
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Created the %s system schedule (every %ds)", HitlAutonomySchedules.SCHEDULE_NAME, seconds);
        } catch (Exception e) {
            LOGGER.warnf("Could not ensure the %s schedule — sample expiry and earned-autonomy evaluation wait until it exists: %s",
                    HitlAutonomySchedules.SCHEDULE_NAME, e.getMessage());
        }
    }
}
