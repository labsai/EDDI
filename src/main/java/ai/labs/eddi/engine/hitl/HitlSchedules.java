/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import java.util.LinkedHashMap;
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
    /**
     * Metadata key carrying the id of the pause this timeout was armed for
     * ({@code HitlDecision.pauseIdOf(pausedAt)}). The timeout's decision names it,
     * so a fire that arrives after the conversation was resumed and paused again —
     * a slow fire, a stale row, a crash-recovery re-arm racing a resume — is
     * refused instead of deciding a newer request nobody timed out on. Absent on
     * rows armed by an older build, which decide whatever pause is current, as
     * before.
     */
    public static final String METADATA_PAUSE_ID_KEY = "pauseId";

    public static final String SURFACE_REGULAR = "regular";
    public static final String SURFACE_GROUP = "group";
    /**
     * A HUMAN group member's turn timeout (I6). Its {@link #METADATA_POLICY_KEY}
     * carries {@code OnHumanTimeout} names (SKIP_TURN/ABORT), NOT a
     * {@code HitlTimeoutPolicy} — the fire handler must branch on this surface
     * BEFORE parsing the policy.
     */
    public static final String SURFACE_GROUP_HUMAN = "group-human";

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

    /**
     * The metadata of an approval-timeout schedule. Every producer goes through
     * here so the pause binding cannot be forgotten by one of them.
     *
     * @param pauseId
     *            {@code HitlDecision.pauseIdOf(pausedAt)}; null leaves the timeout
     *            unbound (it then decides whatever pause is current)
     */
    public static Map<String, Object> timeoutMetadata(String policyName, String surface, String conversationId, String pauseId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_TYPE_KEY, METADATA_TYPE_TIMEOUT);
        metadata.put(METADATA_POLICY_KEY, policyName);
        metadata.put(METADATA_SURFACE_KEY, surface);
        metadata.put(METADATA_CONVERSATION_ID_KEY, conversationId);
        if (pauseId != null && !pauseId.isBlank()) {
            metadata.put(METADATA_PAUSE_ID_KEY, pauseId);
        }
        return metadata;
    }

    /** True if the metadata marks a HITL timeout schedule. */
    public static boolean isHitlTimeout(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_TIMEOUT.equals(metadata.get(METADATA_TYPE_KEY));
    }
}
