/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

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
 *
 * @author ginccc
 */
public record CallerIdentity(String token, String userId, String origin) {

    /** Whether this identity can satisfy a {@code ${caller:token}} reference. */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
