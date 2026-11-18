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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Makes sure the {@code alerts-maintenance} system schedule exists: at startup,
 * and again whenever a rule is saved (so a schedule an administrator deleted
 * comes back as soon as alerts are used). Two nodes starting at once may both
 * create one; {@link #removeDuplicates} then deletes all but the oldest at
 * startup.
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
        removeDuplicates();
    }

    private static int unhealthyRank(ScheduleConfiguration schedule) {
        return schedule.isEnabled() && schedule.getFireStatus() != ScheduleConfiguration.FireStatus.DEAD_LETTERED ? 0 : 1;
    }

    /**
     * Keeps exactly one marked maintenance schedule: an enabled, not dead-lettered
     * one if any, then the oldest ({@code createdAt}, then id). Nodes that start at
     * once can each create one — the REST API then refuses to delete the extra, as
     * it does for the system schedule — and two copies would run every retry and
     * digest pass twice. Every node applies the same rule, so they agree on the
     * survivor and the deletes are idempotent. Not airtight: two nodes can each
     * scan before the other's insert, leaving two copies until the next startup or
     * the next time one is created. (A periodic pass would rescan every schedule
     * each minute for a rare race, so there is none.) Never throws.
     */
    void removeDuplicates() {
        if (!settings.enabled()) {
            return;
        }
        try {
            List<ScheduleConfiguration> marked = new ArrayList<>();
            for (int offset = 0; offset < SCAN_LIMIT; offset += SCAN_PAGE) {
                List<ScheduleConfiguration> page = scheduleStore.readAllSchedules(SCAN_PAGE, offset, true);
                page.stream().filter(s -> AlertSchedules.isMaintenanceSchedule(s.getMetadata())).forEach(marked::add);
                if (page.size() < SCAN_PAGE) {
                    break;
                }
            }
            if (marked.size() < 2) {
                return;
            }
            // The survivor is an enabled, not dead-lettered copy if there is one (a
            // healthy younger copy must not be deleted for a disabled older one), then
            // the oldest. Every node applies the same order, so they agree.
            marked.sort(Comparator.comparingInt(AlertMaintenanceSchedule::unhealthyRank)
                    .thenComparing(ScheduleConfiguration::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ScheduleConfiguration::getId, Comparator.nullsLast(Comparator.naturalOrder())));
            for (ScheduleConfiguration duplicate : marked.subList(1, marked.size())) {
                scheduleStore.deleteSchedule(duplicate.getId());
                LOGGER.warnf("Removed a duplicate %s schedule (id %s); kept %s", AlertSchedules.SCHEDULE_NAME, sanitize(duplicate.getId()),
                        sanitize(marked.get(0).getId()));
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not check for duplicate %s schedules: %s", AlertSchedules.SCHEDULE_NAME, e.getMessage());
        }
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
            // Whichever node creates last sees the others' copies and removes them.
            removeDuplicates();
        } catch (Exception e) {
            LOGGER.warnf("Could not ensure the %s schedule — alert retries and digests wait until it exists: %s", AlertSchedules.SCHEDULE_NAME,
                    e.getMessage());
        }
    }
}
