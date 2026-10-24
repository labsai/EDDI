/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Makes sure the {@code alerts-maintenance} system schedule exists: at startup,
 * and again whenever a rule is saved (so a schedule an administrator deleted
 * comes back as soon as alerts are used). Creating it is idempotent enough: two
 * nodes starting at once may both create one, and every step of the job is a
 * conditional write, so the second copy does no harm.
 */
@ApplicationScoped
public class AlertMaintenanceSchedule {

    private static final Logger LOGGER = Logger.getLogger(AlertMaintenanceSchedule.class);

    private final IScheduleStore scheduleStore;
    private final AlertSettings settings;

    @Inject
    public AlertMaintenanceSchedule(IScheduleStore scheduleStore, AlertSettings settings) {
        this.scheduleStore = scheduleStore;
        this.settings = settings;
    }

    void onStart(@Observes StartupEvent event) {
        ensure();
    }

    /** Creates the schedule when missing; never throws. */
    public void ensure() {
        if (!settings.enabled()) {
            return;
        }
        try {
            if (scheduleStore.readScheduleByName(AlertSchedules.SCHEDULE_NAME).isPresent()) {
                return;
            }
            long seconds = Math.max(15, settings.maintenanceInterval().toSeconds());
            ScheduleConfiguration schedule = new ScheduleConfiguration();
            schedule.setName(AlertSchedules.SCHEDULE_NAME);
            schedule.setTriggerType(ScheduleConfiguration.TriggerType.HEARTBEAT);
            schedule.setHeartbeatIntervalSeconds(seconds);
            schedule.setEnabled(true);
            schedule.setNextFire(Instant.now().plusSeconds(seconds));
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(AlertSchedules.maintenanceMetadata());
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Created the %s system schedule (every %ds)", AlertSchedules.SCHEDULE_NAME, seconds);
        } catch (Exception e) {
            LOGGER.warnf("Could not ensure the %s schedule — alert retries and digests wait until it exists: %s", AlertSchedules.SCHEDULE_NAME,
                    e.getMessage());
        }
    }
}
