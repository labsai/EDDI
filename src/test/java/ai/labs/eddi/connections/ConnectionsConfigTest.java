/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code returnTo} rules get their own tests because the redirect they
 * govern lands on the user immediately after they authenticated at a provider —
 * the moment they are least likely to check the address bar, and therefore the
 * moment an open redirect is most effective.
 */
class ConnectionsConfigTest {

    private static final ConnectionsConfig CONFIG = new ConnectionsConfig(true, "https://eddi.example.com");

    @Test
    @DisplayName("the redirect_uri is built from configuration, never from a request")
    void buildsRedirectUri() {
        assertEquals("https://eddi.example.com/connections/callback", CONFIG.redirectUri());
        assertEquals("https://eddi.example.com/connections/callback", new ConnectionsConfig(true, "https://eddi.example.com/").redirectUri());
    }

    @Test
    @DisplayName("a relative path is accepted — it is what the Manager sends")
    void acceptsRelativePath() {
        assertTrue(CONFIG.isAllowedReturnTo("/manage/connections"));
        assertTrue(CONFIG.isAllowedReturnTo("/manage/connections?tab=linked"));
    }

    @Test
    @DisplayName("a same-origin absolute URL is accepted")
    void acceptsSameOrigin() {
        assertTrue(CONFIG.isAllowedReturnTo("https://eddi.example.com/manage/connections"));
    }

    @Test
    @DisplayName("another host is refused")
    void refusesForeignHost() {
        assertFalse(CONFIG.isAllowedReturnTo("https://evil.example.com/collect"));
        assertFalse(CONFIG.isAllowedReturnTo("http://eddi.example.com/manage"), "a scheme downgrade is a different origin");
    }

    @Test
    @DisplayName("a protocol-relative URL is refused — it is not a relative path")
    void refusesProtocolRelative() {
        // The one that slips past a naive startsWith("/") check: browsers resolve
        // "//evil.example.com" against the current scheme and go to another host.
        assertFalse(CONFIG.isAllowedReturnTo("//evil.example.com/collect"));
        assertFalse(CONFIG.isAllowedReturnTo("//evil.example.com"));
    }

    @Test
    @DisplayName("a backslash is refused — some browsers normalise it to a slash")
    void refusesBackslash() {
        assertFalse(CONFIG.isAllowedReturnTo("/\\evil.example.com"));
        assertFalse(CONFIG.isAllowedReturnTo("\\\\evil.example.com"));
    }

    @Test
    @DisplayName("null and blank fall back to the default page")
    void refusesEmpty() {
        assertFalse(CONFIG.isAllowedReturnTo(null));
        assertFalse(CONFIG.isAllowedReturnTo("   "));
        assertEquals("https://eddi.example.com/manage/connections", CONFIG.defaultReturnTo());
    }
}
