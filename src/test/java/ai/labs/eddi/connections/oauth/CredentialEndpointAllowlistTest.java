/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.connections.ConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allowlist that decides where a CLIENT SECRET may be sent. Its whole
 * reason for existing is that a connection document must not be able to vouch
 * for its own token endpoint.
 */
class CredentialEndpointAllowlistTest {

    private static final CredentialEndpointAllowlist ATLASSIAN = new CredentialEndpointAllowlist(Set.of("https://auth.atlassian.com"));

    @Test
    @DisplayName("an approved origin passes")
    void acceptsApprovedOrigin() {
        assertDoesNotThrow(() -> ATLASSIAN.require("https://auth.atlassian.com/oauth/token", "oauth.tokenUrl"));
    }

    @Test
    @DisplayName("an unapproved origin is refused, and the message says why an operator has to approve it")
    void refusesUnapprovedOrigin() {
        var error = assertThrows(ConnectionException.class, () -> ATLASSIAN.require("https://evil.example.com/token", "oauth.tokenUrl"));

        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
        assertTrue(error.getMessage().contains("client secret"), error.getMessage());
    }

    @Test
    @DisplayName("an empty allowlist refuses everything — fail closed, not open")
    void emptyAllowlistRefusesEverything() {
        var empty = new CredentialEndpointAllowlist(Set.of());

        assertTrue(empty.isEmpty());
        var error = assertThrows(ConnectionException.class, () -> empty.require("https://auth.atlassian.com/oauth/token", "oauth.tokenUrl"));
        assertTrue(error.getMessage().contains("credential-endpoint-allowlist"),
                "an unconfigured allowlist is far more likely than an operator who meant 'anywhere': " + error.getMessage());
    }

    @Test
    @DisplayName("a path on the endpoint does not defeat the origin check")
    void comparesOriginsNotStrings() {
        assertDoesNotThrow(() -> ATLASSIAN.require("https://auth.atlassian.com/a/deep/path?with=query", "oauth.tokenUrl"));
    }

    @Test
    @DisplayName("a subdomain is a different origin")
    void refusesSubdomain() {
        assertThrows(ConnectionException.class, () -> ATLASSIAN.require("https://evil.auth.atlassian.com/token", "oauth.tokenUrl"));
    }

    @Test
    @DisplayName("a null endpoint is not checked — an optional discovery URL is legitimately absent")
    void ignoresNull() {
        assertDoesNotThrow(() -> ATLASSIAN.require(null, "oauth.discoveryUrl"));
        assertDoesNotThrow(() -> ATLASSIAN.require("  ", "oauth.discoveryUrl"));
    }

    @Test
    @DisplayName("a malformed endpoint is a configuration error")
    void refusesMalformed() {
        assertThrows(ConnectionException.class, () -> ATLASSIAN.require("not a url", "oauth.tokenUrl"));
        assertThrows(ConnectionException.class, () -> ATLASSIAN.require("/relative/token", "oauth.tokenUrl"));
    }
}
