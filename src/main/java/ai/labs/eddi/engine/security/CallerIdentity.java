/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

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
 *            {@code scheme://host[:port]} of the request the caller made. A
 *            token is only ever forwarded back to this exact origin, so it
 *            cannot be leaked to a third-party host named in an agent config.
 * @param connectionCredentials
 *            credentials the caller supplied for
 *            {@link ai.labs.eddi.configs.connections.model.Binding#CALLER_SUPPLIED}
 *            connections, keyed by connection name. Never empty-checked into a
 *            fallback: a connection with no entry here fails closed.
 *
 * @author ginccc
 */
public record CallerIdentity(String token, String userId, String origin, Map<String, String> connectionCredentials) {

    /**
     * Defensive copy, and {@code null} normalised to empty.
     * <p>
     * This record is handed to a resolver that decides what leaves the process, and
     * it is bound to a {@link ThreadLocal} for the length of a turn. A caller
     * retaining the map it passed in could otherwise change which credential is
     * sent after the identity was captured — and a null map would turn every lookup
     * into an NPE on a path whose whole job is to fail closed with a readable
     * message.
     */
    public CallerIdentity {
        connectionCredentials = connectionCredentials == null || connectionCredentials.isEmpty()
                ? Map.of()
                : Map.copyOf(connectionCredentials);
    }

    /** An identity carrying no caller-supplied connection credentials. */
    public CallerIdentity(String token, String userId, String origin) {
        this(token, userId, origin, Map.of());
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
