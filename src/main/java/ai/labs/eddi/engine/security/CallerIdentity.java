/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import java.util.List;
import java.util.Map;

/**
 * The authenticated caller behind the conversation turn currently being
 * processed.
 * <p>
 * Captured on the request thread and carried to the pipeline worker thread by
 * {@link CallerIdentityContext}. It is deliberately <em>not</em> part of
 * {@code IConversationMemory}: the raw token must never reach the conversation
 * store, an export, or the debugger.
 *
 * @param token
 *            the caller's raw bearer token, or {@code null} when the request
 *            carried no token (e.g. OIDC disabled)
 * @param userId
 *            the caller's principal name, or {@code null} when anonymous
 * @param origin
 *            normalized {@code scheme://host:port} of the request the caller
 *            made. A token is only ever forwarded back to this exact origin, so
 *            it cannot be leaked to a third-party host named in an agent
 *            config.
 * @param connectionCredentials
 *            credentials the caller supplied for connections whose binding is
 *            {@code CALLER_SUPPLIED}, keyed by connection name. Never
 *            empty-checked into a fallback: a connection with no entry here
 *            fails closed. Named rather than {@code @link}ed because an import
 *            used only from Javadoc is what Checkstyle's {@code UnusedImports}
 *            calls unused.
 * @param admin
 *            whether the caller held the {@code eddi-admin} role on the request
 *            that captured this identity. Lets engine code that acts for this
 *            caller on a worker thread — where there is no
 *            {@code SecurityIdentity} to ask — give an administrator the same
 *            access the request-scoped guards give them (see
 *            {@code ResourceAccessGuard.principalMayUse}). Never trusted for a
 *            different principal than {@link #userId}.
 * @param teamSubjects
 *            the team subjects ({@code team:<group path>}) on the token of the
 *            request that captured this identity, in token order, the caller's
 *            personal space excluded — the same derivation as
 *            {@code ResourceAccessGuard.currentTeamSubjects()}. A
 *            <em>snapshot</em>: taken once at the request boundary, never
 *            refreshed during the turn, never persisted. Like {@link #admin} it
 *            describes {@link #userId} only. Empty means "no team", not
 *            "unknown": a turn with no request token behind it (a schedule, a
 *            cadence, a HITL resume by somebody other than the owner) has no
 *            teams, so team features fail closed. Never filled from the user
 *            directory's last-seen memberships.
 *
 * @author ginccc
 */
public record CallerIdentity(String token, String userId, String origin, Map<String, String> connectionCredentials, boolean admin,
        List<String> teamSubjects) {

    /**
     * Defensive copies, and {@code null} normalised to empty.
     * <p>
     * This record is handed to a resolver that decides what leaves the process, and
     * it is bound to a {@link ThreadLocal} for the length of a turn. A caller
     * retaining the map or list it passed in could otherwise change which
     * credential is sent, or which teams the turn belongs to, after the identity
     * was captured — and a null would turn every lookup into an NPE on a path whose
     * whole job is to fail closed with a readable message.
     */
    public CallerIdentity {
        connectionCredentials = connectionCredentials == null || connectionCredentials.isEmpty()
                ? Map.of()
                : Map.copyOf(connectionCredentials);
        teamSubjects = teamSubjects == null || teamSubjects.isEmpty()
                ? List.of()
                : teamSubjects.stream().filter(subject -> subject != null && !subject.isBlank()).distinct().toList();
    }

    /** An identity that belongs to no team. */
    public CallerIdentity(String token, String userId, String origin, Map<String, String> connectionCredentials, boolean admin) {
        this(token, userId, origin, connectionCredentials, admin, List.of());
    }

    /** An identity carrying no caller-supplied connection credentials. */
    public CallerIdentity(String token, String userId, String origin) {
        this(token, userId, origin, Map.of());
    }

    /** A non-admin identity. */
    public CallerIdentity(String token, String userId, String origin, Map<String, String> connectionCredentials) {
        this(token, userId, origin, connectionCredentials, false);
    }

    /**
     * Whether this identity is an administrator acting as {@code principal} — both
     * halves required, so an admin's identity never vouches for someone else.
     */
    public boolean isAdminActingAs(String principal) {
        return admin && principal != null && principal.equals(userId);
    }

    /**
     * This identity with no teams.
     * <p>
     * For an identity that acts in somebody else's turn — the approver of a HITL
     * resume. Their token and principal are theirs to act with on the call they
     * approved; their team memberships are not, because the turn belongs to the
     * conversation's owner, and a team feature must not admit the owner's turn
     * through a team only the approver is in.
     */
    public CallerIdentity withoutTeamSubjects() {
        return teamSubjects.isEmpty() ? this : new CallerIdentity(token, userId, origin, connectionCredentials, admin, List.of());
    }

    /**
     * Names no secret and no team: the token and credential values are redacted,
     * and connection names and team subjects are counted rather than listed.
     * <p>
     * The generated record {@code toString} would print all of them, and a record
     * that reaches a log line through string concatenation or a debug statement
     * must not carry a bearer token or a user's team memberships with it.
     */
    @Override
    public String toString() {
        return "CallerIdentity[token=" + (hasToken() ? "<redacted>" : "none") + ", userId=" + userId + ", origin=" + origin
                + ", connectionCredentials=" + connectionCredentials.size() + ", admin=" + admin + ", teamSubjects="
                + teamSubjects.size() + "]";
    }

    /** Whether this identity can satisfy a {@code ${caller:token}} reference. */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /**
     * The credential this caller supplied for {@code connectionName}, or
     * {@code null}.
     * <p>
     * Null rather than blank or a sentinel, so the one caller — the
     * {@code CALLER_SUPPLIED} branch of {@code ConnectionResolver} — has a single
     * unambiguous thing to test before failing closed.
     */
    public String connectionCredential(String connectionName) {
        if (connectionName == null) {
            return null;
        }
        String value = connectionCredentials.get(connectionName);
        return value == null || value.isBlank() ? null : value;
    }
}
