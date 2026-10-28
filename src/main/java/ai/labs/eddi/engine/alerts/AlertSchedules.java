/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import java.util.Map;

/**
 * The one system schedule alerts run on: {@code alerts-maintenance}, a
 * heartbeat recognised by its metadata, which {@code ScheduleFireExecutor}
 * routes to {@link AlertMaintenanceService} instead of a conversation.
 */
public final class AlertSchedules {

    public static final String SCHEDULE_NAME = "alerts-maintenance";
    public static final String METADATA_TYPE_KEY = "alertType";
    public static final String METADATA_TYPE_MAINTENANCE = "alert_maintenance";

    private AlertSchedules() {
    }

    public static Map<String, Object> maintenanceMetadata() {
        return Map.of(METADATA_TYPE_KEY, METADATA_TYPE_MAINTENANCE);
    }

    public static boolean isMaintenanceSchedule(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_MAINTENANCE.equals(metadata.get(METADATA_TYPE_KEY));
    }

    /**
     * Whether the metadata claims any alert role — refused on user-created
     * schedules.
     */
    public static boolean claimsAlertType(Map<String, Object> metadata) {
        return metadata != null && metadata.containsKey(METADATA_TYPE_KEY);
    }

    /**
     * Whether a name is the reserved {@value #SCHEDULE_NAME} — refused on
     * user-created schedules, so a hand-made row can never pass for the system one.
     * Compared trimmed and case-insensitively.
     */
    public static boolean isReservedName(String name) {
        return name != null && SCHEDULE_NAME.equalsIgnoreCase(name.trim());
    }
}
