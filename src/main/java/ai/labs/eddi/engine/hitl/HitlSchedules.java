/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import java.util.Map;

/**
 * Single source of truth for the naming and metadata contract of HITL timeout
 * schedules. Producers (ConversationService, GroupConversationService, crash
 * recovery), the dispatcher (ScheduleFireExecutor → HitlTimeoutHandler), and
 * the REST guard (RestScheduleStore) must all agree on these strings — a drift
 * between them silently disables timeout firing or the schedule-surface
 * protections.
 */
public final class HitlSchedules {

    private HitlSchedules() {
    }

    /** Metadata key marking a schedule as HITL-managed. */
    public static final String METADATA_TYPE_KEY = "hitlType";
    /** Metadata value for one-shot approval-timeout schedules. */
    public static final String METADATA_TYPE_TIMEOUT = "hitl_timeout";
    /** Metadata key carrying the {@code HitlTimeoutPolicy} name. */
    public static final String METADATA_POLICY_KEY = "policy";
    /** Metadata key carrying the surface discriminator. */
    public static final String METADATA_SURFACE_KEY = "surface";
    /** Metadata key carrying the (group) conversation id to decide. */
    public static final String METADATA_CONVERSATION_ID_KEY = "conversationId";

    public static final String SURFACE_REGULAR = "regular";
    public static final String SURFACE_GROUP = "group";

    private static final String NAME_PREFIX_REGULAR = "hitl-timeout-";
    private static final String NAME_PREFIX_GROUP = "hitl-timeout-group-";

    /** Schedule name for a regular conversation's approval timeout. */
    public static String regularTimeoutScheduleName(String conversationId) {
        return NAME_PREFIX_REGULAR + conversationId;
    }

    /** Schedule name for a group discussion's approval timeout. */
    public static String groupTimeoutScheduleName(String groupConversationId) {
        return NAME_PREFIX_GROUP + groupConversationId;
    }

    /** True if the metadata marks a HITL timeout schedule. */
    public static boolean isHitlTimeout(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_TIMEOUT.equals(metadata.get(METADATA_TYPE_KEY));
    }
}
