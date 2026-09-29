/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.runtime.internal.DreamService;
import ai.labs.eddi.engine.runtime.internal.TeamCadenceService;

import java.util.Map;

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
 * <p>
 * Two refinements, both off in {@link #visibleTo}, carry the workspace rules
 * into the same query:
 * <ul>
 * <li>{@link #sharedOnlyIfCreatedByCaller()}: under workspace enforcement a
 * shared schedule belongs to the team of what it drives, which a schedule query
 * cannot judge; a listing then admits only the shared schedules the caller
 * created ({@code createdBy}).</li>
 * <li>{@link #withTeamCadences()}: a team cadence runs as its creator but
 * belongs to its group, so when the caller may see every group (workspaces not
 * enforced) every cadence is admitted, whoever created it.</li>
 * </ul>
 *
 * @param unrestricted
 *            {@code true} for no owner filter at all (admins, authorization
 *            disabled, internal callers)
 * @param callerId
 *            the caller's principal name; {@code null} when it has none.
 *            Ignored when {@code unrestricted}
 * @param sharedCreatedByCallerOnly
 *            whether a shared schedule is admitted only when {@code createdBy}
 *            is the caller
 * @param includeTeamCadences
 *            whether every team cadence schedule is admitted
 */
public record ScheduleOwnerScope(boolean unrestricted, String callerId, boolean sharedCreatedByCallerOnly,
        boolean includeTeamCadences) {

    /** No owner filter: every schedule is visible. */
    public static final ScheduleOwnerScope ALL = new ScheduleOwnerScope(true, null);

    /** The placeholder owner of a system schedule, visible to everyone. */
    public static final String SHARED_OWNER = DreamService.SCHEDULER_PLACEHOLDER_USER_ID;

    public ScheduleOwnerScope {
        if (unrestricted || callerId == null || callerId.isBlank()) {
            callerId = null;
        }
        if (unrestricted) {
            sharedCreatedByCallerOnly = false;
            includeTeamCadences = false;
        }
    }

    /** The owner rule alone, without either refinement. */
    public ScheduleOwnerScope(boolean unrestricted, String callerId) {
        this(unrestricted, callerId, false, false);
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
     * This scope, admitting a shared schedule only when the caller created it.
     */
    public ScheduleOwnerScope sharedOnlyIfCreatedByCaller() {
        return new ScheduleOwnerScope(unrestricted, callerId, true, includeTeamCadences);
    }

    /** This scope, also admitting every team cadence schedule. */
    public ScheduleOwnerScope withTeamCadences() {
        return new ScheduleOwnerScope(unrestricted, callerId, sharedCreatedByCallerOnly, true);
    }

    /**
     * Whether a schedule running as {@code owner} is visible in this scope, judged
     * on the owner alone — exact for a scope without refinements; see
     * {@link #admits(String, String, Map)}.
     */
    public boolean admits(String owner) {
        return admits(owner, null, null);
    }

    /**
     * Whether a schedule running as {@code owner}, created by {@code createdBy} and
     * carrying {@code metadata}, is visible in this scope: the in-memory form of
     * the predicate the stores push into their queries.
     */
    public boolean admits(String owner, String createdBy, Map<String, Object> metadata) {
        if (unrestricted) {
            return true;
        }
        if (callerId != null && callerId.equals(owner)) {
            return true;
        }
        if (isShared(owner)) {
            return !sharedCreatedByCallerOnly || (callerId != null && callerId.equals(createdBy));
        }
        return includeTeamCadences && TeamCadenceService.isTeamCadenceSchedule(metadata);
    }

    /** A schedule with no owner, or the system placeholder, is shared. */
    public static boolean isShared(String owner) {
        return owner == null || owner.isBlank() || SHARED_OWNER.equals(owner);
    }
}
