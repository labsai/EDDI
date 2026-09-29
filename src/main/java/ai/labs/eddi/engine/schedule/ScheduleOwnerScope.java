/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.runtime.internal.DreamService;

/**
 * Which schedules a paged listing may return, judged by the identity each
 * schedule runs as ({@code userId}). Pushed into the store query, like the
 * HITL-timeout redaction, so {@code limit}/{@code offset} count only rows the
 * caller can see; see
 * {@link IScheduleStore#readAllSchedules(int, int, boolean, ScheduleOwnerScope)}.
 * <p>
 * A restricted scope admits exactly what the REST layer's owner check allows a
 * non-admin: schedules owned by the caller, plus shared ones: no owner
 * ({@code null} or blank) or the system placeholder
 * {@link DreamService#SCHEDULER_PLACEHOLDER_USER_ID}. A restricted scope with
 * no caller id (an anonymous or nameless identity, which owns nothing) admits
 * only the shared ones.
 *
 * @param unrestricted
 *            {@code true} for no owner filter at all (admins, authorization
 *            disabled, internal callers)
 * @param callerId
 *            the caller's principal name; {@code null} when it has none.
 *            Ignored when {@code unrestricted}
 */
public record ScheduleOwnerScope(boolean unrestricted, String callerId) {

    /** No owner filter: every schedule is visible. */
    public static final ScheduleOwnerScope ALL = new ScheduleOwnerScope(true, null);

    /** The placeholder owner of a system schedule, visible to everyone. */
    public static final String SHARED_OWNER = DreamService.SCHEDULER_PLACEHOLDER_USER_ID;

    public ScheduleOwnerScope {
        if (unrestricted || callerId == null || callerId.isBlank()) {
            callerId = null;
        }
    }

    /**
     * A scope restricted to {@code callerId}'s own schedules plus shared ones.
     *
     * @param callerId
     *            the caller's principal name, or {@code null}/blank when it has
     *            none (then only shared schedules are visible)
     */
    public static ScheduleOwnerScope visibleTo(String callerId) {
        return new ScheduleOwnerScope(false, callerId);
    }

    /**
     * Whether a schedule running as {@code owner} is visible in this scope: the
     * in-memory form of the predicate the stores push into their queries.
     */
    public boolean admits(String owner) {
        if (unrestricted || isShared(owner)) {
            return true;
        }
        return callerId != null && callerId.equals(owner);
    }

    /** A schedule with no owner, or the system placeholder, is shared. */
    public static boolean isShared(String owner) {
        return owner == null || owner.isBlank() || SHARED_OWNER.equals(owner);
    }
}
