/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isOAuth} replaced a two-term disjunction that was spelled out at
 * several call sites — the vault-availability gate on write, the startup guard,
 * the resolver. Those sites now agree by construction, which is the point, and
 * makes this one method worth its own test.
 */
class AuthTypeTest {

    @Test
    @DisplayName("isOAuth is true for exactly the two flows that need a token endpoint and a stored grant")
    void namesExactlyTheOAuthTypes() {
        assertTrue(AuthType.OAUTH2_AUTHORIZATION_CODE.isOAuth(), "the per-user flow stores a grant, so it needs an active vault");
        assertTrue(AuthType.OAUTH2_CLIENT_CREDENTIALS.isOAuth(), "so does the service-account flow, and it is the one easiest to forget");
        assertFalse(AuthType.STATIC.isOAuth(), "a static header has no token endpoint; treating it as OAuth would demand a vault it never uses");
        assertFalse(AuthType.BASIC.isOAuth());
    }

    @Test
    @DisplayName("every AuthType is classified above, so a new one cannot slip past unconsidered")
    void everyTypeIsClassified() {
        // The failure the helper exists to prevent: a third OAuth flow (device code,
        // JWT bearer) added without reaching every gate, producing a connection that
        // saves without a vault and then fails at the moment the token comes back.
        // This test is the tripwire — if it fails, decide the new constant's answer
        // above rather than only widening the number.
        assertEquals(4, AuthType.values().length,
                "a new AuthType was added; classify it in namesExactlyTheOAuthTypes and check isOAuth still answers correctly for it");
    }
}
