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
    @DisplayName("a scheme's default port written out explicitly is still the same origin")
    void foldsDefaultPorts() {
        // Raw port comparison makes https://host and https://host:443 two origins, so
        // a base URL written one way rejects a returnTo written the other — and the
        // user lands on the default page immediately after authenticating, with
        // nothing on the page saying why. Both directions, because the fold has to
        // apply to each side against its OWN scheme.
        assertTrue(CONFIG.isAllowedReturnTo("https://eddi.example.com:443/manage/connections"),
                "an explicit :443 on the returnTo is the same origin as the base URL, and refusing it strands the user on the wrong page");

        var explicitPortBase = new ConnectionsConfig(true, "https://eddi.example.com:443");
        assertTrue(explicitPortBase.isAllowedReturnTo("https://eddi.example.com/manage/connections"),
                "an explicit :443 in the configured base URL must not make every implicit-port returnTo foreign");

        var httpBase = new ConnectionsConfig(true, "http://localhost:80");
        assertTrue(httpBase.isAllowedReturnTo("http://localhost/manage/connections"), "http's default port folds the same way https's does");
    }

    @Test
    @DisplayName("a genuinely different port is still a different origin")
    void refusesDifferentPort() {
        // The other half of the fold: it must normalise the scheme's own default and
        // nothing else, or "same origin" stops meaning anything.
        assertFalse(CONFIG.isAllowedReturnTo("https://eddi.example.com:8443/manage/connections"));
        assertFalse(CONFIG.isAllowedReturnTo("https://eddi.example.com:80/manage/connections"), "80 is not https's default port");
    }

    @Test
    @DisplayName("a path that URI.create will not parse is refused here, where the user can still be redirected")
    void refusesUnparseablePath() {
        // Parseability is part of "allowed". The value is turned into a URI in the
        // CALLBACK — after the single-use state is consumed and the grant is stored —
        // so a path that will not parse has to be caught at authorize time, where
        // falling back to the default page costs nothing.
        assertFalse(CONFIG.isAllowedReturnTo("/manage/my connections"), "an unencoded space cannot be turned into a URI");
        assertFalse(CONFIG.isAllowedReturnTo("/manage/connections?id={agentId}"), "an unencoded brace cannot be turned into a URI");
        assertTrue(CONFIG.isAllowedReturnTo("/manage/connections?id=abc%20def"),
                "a properly encoded path must still be accepted, or the check has simply become a refusal");
    }

    @Test
    @DisplayName("null and blank fall back to the default page")
    void refusesEmpty() {
        assertFalse(CONFIG.isAllowedReturnTo(null));
        assertFalse(CONFIG.isAllowedReturnTo("   "));
        assertEquals("https://eddi.example.com/manage/connections", CONFIG.defaultReturnTo());
    }
}
