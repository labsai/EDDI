/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;

import java.util.Map;

/**
 * The reserved schedule that runs oversight maintenance — sample expiry,
 * earned-autonomy evaluation, retention and the first-start rebuild — on one
 * node at a time through the schedule store's claim and lease.
 *
 * @since 6.7.0
 */
public final class HitlAutonomySchedules {

    public static final String SCHEDULE_NAME = "hitl-autonomy-maintenance";
    public static final String METADATA_TYPE_KEY = "hitlAutonomyType";
    public static final String METADATA_TYPE_MAINTENANCE = "maintenance";

    private HitlAutonomySchedules() {
    }

    public static Map<String, Object> maintenanceMetadata() {
        return Map.of(METADATA_TYPE_KEY, METADATA_TYPE_MAINTENANCE);
    }

    /** Whether a schedule's metadata marks the maintenance schedule. */
    public static boolean isMaintenance(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_MAINTENANCE.equals(metadata.get(METADATA_TYPE_KEY));
    }

    /**
     * Whether a client-supplied schedule body claims the reserved name or marker —
     * refused over REST: only the server creates it.
     */
    public static boolean claims(ScheduleConfiguration schedule) {
        return schedule != null && (schedule.getMetadata() != null && schedule.getMetadata().containsKey(METADATA_TYPE_KEY)
                || schedule.getName() != null && SCHEDULE_NAME.equalsIgnoreCase(schedule.getName().trim()));
    }

    /** Whether a stored schedule is the server's own. */
    public static boolean isSystem(ScheduleConfiguration schedule) {
        return schedule != null && (schedule.getMetadata() != null && schedule.getMetadata().containsKey(METADATA_TYPE_KEY)
                || SCHEDULE_NAME.equals(schedule.getName()) && schedule.getAgentId() == null);
    }
}
