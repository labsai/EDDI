/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one definition of a usable public base URL and of a clean allowlist,
 * shared by the boot check on a pinned value, the write boundary, and the
 * request-time check on a stored value.
 */
class ConnectionSettingsRulesTest {

    private static final String LABEL = "publicBaseUrl";

    @ParameterizedTest
    @ValueSource(strings = {"https://eddi.example.com", "https://eddi.example.com/", "https://eddi.example.com:8443", " https://eddi.example.com ",
            "HTTPS://eddi.example.com"})
    @DisplayName("a bare https origin is accepted and returned trimmed")
    void acceptsBareHttpsOrigin(String value) {
        assertEquals(value.trim(), ConnectionSettingsRules.requireBarePublicOrigin(value, LABEL, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://eddi.example.com", "https://eddi.example.com/eddi", "https://eddi.example.com?tenant=a",
            "https://eddi.example.com#x", "https://ops@eddi.example.com", "http://localhost:7070", "https:eddi.example.com"})
    @DisplayName("outside dev and test, every shape a provider would not match exactly is refused, naming the setting and the value")
    void refusesWhatAProviderWouldNotMatch(String value) {
        var failure = assertThrows(IllegalArgumentException.class, () -> ConnectionSettingsRules.requireBarePublicOrigin(value, LABEL, false));

        assertTrue(failure.getMessage().startsWith(LABEL), failure.getMessage());
        assertTrue(failure.getMessage().contains("bare https origin"), failure.getMessage());
        assertTrue(failure.getMessage().contains(value), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:7070", "http://127.0.0.1:7070"})
    @DisplayName("dev and test also accept plain http on loopback")
    void devAcceptsLoopbackHttp(String value) {
        assertEquals(value, ConnectionSettingsRules.requireBarePublicOrigin(value, LABEL, true));
    }

    @Test
    @DisplayName("dev and test still refuse plain http to a remote host, and say what else they accept")
    void devRefusesRemoteHttp() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ConnectionSettingsRules.requireBarePublicOrigin("http://eddi.example.com", LABEL, true));

        assertTrue(failure.getMessage().contains("http://localhost"), failure.getMessage());
    }

    @Test
    @DisplayName("an empty value is refused with the reason it cannot be inferred")
    void refusesEmpty() {
        var failure = assertThrows(IllegalArgumentException.class, () -> ConnectionSettingsRules.requireBarePublicOrigin("  ", LABEL, false));

        assertTrue(failure.getMessage().contains("redirect_uri"), failure.getMessage());
    }

    @Test
    @DisplayName("an unparseable value keeps the parse failure as its cause")
    void unparseableKeepsItsCause() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ConnectionSettingsRules.requireBarePublicOrigin("https://eddi example.com", LABEL, false));

        assertTrue(failure.getMessage().contains("not a valid URL"), failure.getMessage());
        assertNotNull(failure.getCause());
    }

    @Test
    @DisplayName("origins are canonicalised, de-duplicated in order, and blank entries skipped")
    void canonicalisesOrigins() {
        List<String> origins = ConnectionSettingsRules.canonicalOrigins(
                Arrays.asList("HTTPS://Auth.Atlassian.com:443", null, " ", "https://auth.atlassian.com", "https://oauth2.googleapis.com"), "list");

        assertEquals(List.of("https://auth.atlassian.com", "https://oauth2.googleapis.com"), origins);
    }

    @Test
    @DisplayName("a null collection is an empty list")
    void nullIsEmpty() {
        assertEquals(List.of(), ConnectionSettingsRules.canonicalOrigins(null, "list"));
    }

    @Test
    @DisplayName("an entry that is not a bare origin is refused, naming the list")
    void refusesNonOrigin() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ConnectionSettingsRules.canonicalOrigins(List.of("https://auth.atlassian.com/oauth/token"), "credentialEndpointAllowlist"));

        assertTrue(failure.getMessage().contains("credentialEndpointAllowlist"), failure.getMessage());
    }
}
