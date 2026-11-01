/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.datastore.IResourceStore;
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

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
    /** Page size and upper bound of the fallback scan for the marked schedule. */
    static final int SCAN_PAGE = 200;
    static final int SCAN_LIMIT = 10_000;

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

    /**
     * Whether the real schedule exists. Judged by its maintenance marker, not by
     * its name alone: before the name was reserved a user could create a schedule
     * called {@code alerts-maintenance}, which never runs the maintenance pass. In
     * that rare case the store is scanned for the marked row.
     */
    boolean installed() throws IResourceStore.ResourceStoreException {
        Optional<ScheduleConfiguration> byName = scheduleStore.readScheduleByName(AlertSchedules.SCHEDULE_NAME);
        if (byName.isEmpty()) {
            return false;
        }
        if (AlertSchedules.isMaintenanceSchedule(byName.get().getMetadata())) {
            return true;
        }
        LOGGER.warnf("A schedule named %s exists without the maintenance marker (id %s) — it is not the system schedule",
                AlertSchedules.SCHEDULE_NAME, sanitize(byName.get().getId()));
        for (int offset = 0; offset < SCAN_LIMIT; offset += SCAN_PAGE) {
            List<ScheduleConfiguration> page = scheduleStore.readAllSchedules(SCAN_PAGE, offset, true);
            if (page.stream().anyMatch(s -> AlertSchedules.isMaintenanceSchedule(s.getMetadata()))) {
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
        if (!settings.enabled()) {
            return;
        }
        try {
            if (installed()) {
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
