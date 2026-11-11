/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;

import java.util.Map;

/**
 * The reserved schedule the Agent Health rollup runs on —
 * {@value #SCHEDULE_NAME}, a heartbeat recognised by its metadata, which
 * {@code ScheduleFireExecutor} routes to {@link HealthRollupService} instead of
 * a conversation. Running under the schedule store's claim and lease makes one
 * node roll up at a time.
 */
public final class HealthSchedules {

    public static final String SCHEDULE_NAME = "health-rollup";
    public static final String METADATA_TYPE_KEY = "healthType";
    public static final String METADATA_TYPE_ROLLUP = "rollup";

    private HealthSchedules() {
    }

    public static Map<String, Object> rollupMetadata() {
        return Map.of(METADATA_TYPE_KEY, METADATA_TYPE_ROLLUP);
    }

    /** Whether a schedule's metadata marks the rollup schedule. */
    public static boolean isRollup(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_ROLLUP.equals(metadata.get(METADATA_TYPE_KEY));
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
