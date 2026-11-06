/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One event of the per-user stream ({@code GET /me/events}): that something
 * concerning the signed-in user happened, never what.
 * <p>
 * <b>Identifiers and kinds only.</b> No message text, no title, no prompt, no
 * reason a designer wrote, no tool argument: a client that wants to show
 * anything fetches it through the endpoint it would use anyway, which checks
 * access again. That keeps the stream harmless if an event ever reached the
 * wrong tab, and keeps it a hint — an event that is lost, late or repeated
 * changes nothing a refetch would not.
 *
 * @param id
 *            opaque, unique per event
 * @param kind
 *            what happened — one of the {@code KIND_*} constants; also the SSE
 *            {@code event:} name
 * @param ts
 *            when it was raised, epoch milliseconds
 * @param surface
 *            {@code regular} (a 1:1 conversation) or {@code group}, where it
 *            applies
 * @param conversationId
 *            the 1:1 conversation it concerns
 * @param agentId
 *            that conversation's agent
 * @param groupId
 *            the group it concerns
 * @param groupConversationId
 *            the group discussion it concerns
 * @param state
 *            a short enum value: a decision's outcome ({@code APPROVED},
 *            {@code REJECTED}, {@code CANCELLED}, …), a discussion's state
 *            ({@code COMPLETED}, {@code FAILED}, …), or a fire's status
 * @param jobKind
 *            for a long job: {@code continuation} or {@code schedule}
 * @param scheduleId
 *            the schedule whose fire it reports
 * @param notificationId
 *            the workspace notification it announces
 * @param notificationType
 *            that notification's type ({@code SHARED_WITH_YOU}, …)
 * @param yourTurn
 *            set per recipient on {@link #KIND_TEAM_WAITING_FOR_HUMAN}:
 *            {@code true} for the member whose turn it is, {@code false} for
 *            everybody else told; absent on other kinds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserEvent(String id, String kind, long ts, String surface, String conversationId, String agentId, String groupId,
        String groupConversationId, String state, String jobKind, String scheduleId, String notificationId, String notificationType,
        Boolean yourTurn) {

    /**
     * A turn or tool call paused for approval that the recipient may decide, or a
     * group discussion paused at a phase or task gate.
     */
    public static final String KIND_APPROVAL_PENDING = "approval.pending";
    /** A pending approval was decided, cancelled or timed out. */
    public static final String KIND_APPROVAL_DECIDED = "approval.decided";
    /** A group discussion started (or a continuation round of it). */
    public static final String KIND_TEAM_STARTED = "team.started";
    /** A group discussion reached a terminal state. */
    public static final String KIND_TEAM_ENDED = "team.ended";
    /** A group discussion is waiting for a HUMAN member's turn. */
    public static final String KIND_TEAM_WAITING_FOR_HUMAN = "team.waiting_for_human";
    /** A long job finished: the last turn of a self-continuation chain ran. */
    public static final String KIND_JOB_FINISHED = "job.finished";
    /**
     * A scheduled run on the recipient's conversation completed (one fire, not a
     * whole chain).
     */
    public static final String KIND_JOB_RUN_COMPLETED = "job.run_completed";
    /** A scheduled run or a continuation turn of a long job failed. */
    public static final String KIND_JOB_FAILED = "job.failed";
    /** A workspace notification landed in the recipient's inbox. */
    public static final String KIND_NOTIFICATION_CREATED = "notification.created";

    /** Every kind this stream sends; the client's type mirrors it. */
    public static final Set<String> KINDS = Set.of(KIND_APPROVAL_PENDING, KIND_APPROVAL_DECIDED, KIND_TEAM_STARTED, KIND_TEAM_ENDED,
            KIND_TEAM_WAITING_FOR_HUMAN, KIND_JOB_FINISHED, KIND_JOB_RUN_COMPLETED, KIND_JOB_FAILED, KIND_NOTIFICATION_CREATED);

    public static final String SURFACE_REGULAR = "regular";
    public static final String SURFACE_GROUP = "group";
    public static final String JOB_CONTINUATION = "continuation";
    public static final String JOB_SCHEDULE = "schedule";

    /** A new event of {@code kind}, stamped now, every other field empty. */
    public static UserEvent of(String kind) {
        return new UserEvent(UUID.randomUUID().toString(), kind, System.currentTimeMillis(), null, null, null, null, null, null, null, null,
                null, null, null);
    }

    public UserEvent withSurface(String value) {
        return new UserEvent(id, kind, ts, value, conversationId, agentId, groupId, groupConversationId, state, jobKind, scheduleId,
                notificationId, notificationType, yourTurn);
    }

    public UserEvent withConversation(String conversation, String agent) {
        return new UserEvent(id, kind, ts, surface, conversation, agent, groupId, groupConversationId, state, jobKind, scheduleId,
                notificationId, notificationType, yourTurn);
    }

    public UserEvent withGroup(String group, String groupConversation) {
        return new UserEvent(id, kind, ts, surface, conversationId, agentId, group, groupConversation, state, jobKind, scheduleId,
                notificationId, notificationType, yourTurn);
    }

    public UserEvent withState(String value) {
        return new UserEvent(id, kind, ts, surface, conversationId, agentId, groupId, groupConversationId, value, jobKind, scheduleId,
                notificationId, notificationType, yourTurn);
    }

    public UserEvent withJob(String job, String schedule) {
        return new UserEvent(id, kind, ts, surface, conversationId, agentId, groupId, groupConversationId, state, job, schedule,
                notificationId, notificationType, yourTurn);
    }

    public UserEvent withNotification(String notification, String type) {
        return new UserEvent(id, kind, ts, surface, conversationId, agentId, groupId, groupConversationId, state, jobKind, scheduleId,
                notification, type, yourTurn);
    }

    public UserEvent withYourTurn(Boolean value) {
        return new UserEvent(id, kind, ts, surface, conversationId, agentId, groupId, groupConversationId, state, jobKind, scheduleId,
                notificationId, notificationType, value);
    }

    /** As a plain map, for the cluster event payload. */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("kind", kind);
        map.put("ts", ts);
        putIfSet(map, "surface", surface);
        putIfSet(map, "conversationId", conversationId);
        putIfSet(map, "agentId", agentId);
        putIfSet(map, "groupId", groupId);
        putIfSet(map, "groupConversationId", groupConversationId);
        putIfSet(map, "state", state);
        putIfSet(map, "jobKind", jobKind);
        putIfSet(map, "scheduleId", scheduleId);
        putIfSet(map, "notificationId", notificationId);
        putIfSet(map, "notificationType", notificationType);
        return map;
    }

    /**
     * Rebuilt from {@link #toMap()} on another node; {@code null} when the map is
     * not a user event of a kind this node knows (a newer node's kind is dropped,
     * not forwarded unchecked).
     */
    static UserEvent fromMap(Map<?, ?> map) {
        if (map == null || !(map.get("id") instanceof String id) || !(map.get("kind") instanceof String kind) || !KINDS.contains(kind)) {
            return null;
        }
        long ts = map.get("ts") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        return new UserEvent(id, kind, ts, str(map, "surface"), str(map, "conversationId"), str(map, "agentId"), str(map, "groupId"),
                str(map, "groupConversationId"), str(map, "state"), str(map, "jobKind"), str(map, "scheduleId"), str(map, "notificationId"),
                str(map, "notificationType"), null);
    }

    private static void putIfSet(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String str(Map<?, ?> map, String key) {
        return map.get(key) instanceof String s ? s : null;
    }
}
