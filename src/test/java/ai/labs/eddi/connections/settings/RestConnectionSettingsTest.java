/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.settings.ConnectionSettingsView.Source;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The settings resource: the write boundary for everything that used to need a
 * restart, and the one place that can say where an effective value came from.
 */
class RestConnectionSettingsTest {

    private InMemorySettingsStore store;
    private SecurityIdentity identity;

    @BeforeEach
    void setUp() {
        store = new InMemorySettingsStore();
        identity = mock(SecurityIdentity.class);
        Principal principal = () -> "alice";
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
    }

    // --- Reading -------------------------------------------------------------

    @Test
    @DisplayName("a fresh deployment reads as defaults, with nothing to warn about while the feature is off")
    void freshDeploymentReadsAsDefaults() {
        var view = resource(new ConnectionSettings()).readSettings();

        assertEquals(false, view.enabled().value());
        assertEquals(Source.DEFAULT, view.enabled().source());
        assertEquals(ConnectionsConfig.ENABLED, view.enabled().property(), "the property is named even when it is not what set the value");
        assertNull(view.publicBaseUrl().value());
        assertEquals(List.of(), view.credentialEndpointAllowlist().value());
        assertNull(view.redirectUri());
        assertNull(view.updatedBy());
        assertTrue(view.warnings().isEmpty(), view.warnings().toString());
    }

    @Test
    @DisplayName("a read reflects another instance's write at once, not after the cache expires")
    void readBypassesTheCache() {
        var resource = resource(new ConnectionSettings());
        assertEquals(false, resource.readSettings().enabled().value());

        store.put(new ConnectionSettings(true, null, null, null), "bob");

        assertEquals(true, resource.readSettings().enabled().value());
    }

    // --- Writing -------------------------------------------------------------

    @Test
    @DisplayName("a write takes effect immediately and records who made it")
    void writeTakesEffectImmediately() {
        var config = config(new ConnectionSettings());
        var resource = new RestConnectionSettings(config, store, identity);

        var view = resource.updateSettings(
                new ConnectionSettings(true, "https://eddi.example.com/", List.of("https://auth.atlassian.com"), null));

        assertTrue(config.isEnabled(), "no restart, and no waiting for the cache on the instance that took the write");
        assertEquals(Source.STORED, view.enabled().source());
        assertEquals("https://eddi.example.com/", view.publicBaseUrl().value());
        assertEquals("https://eddi.example.com/connections/callback", view.redirectUri());
        assertEquals(List.of("https://auth.atlassian.com"), view.credentialEndpointAllowlist().value());
        assertEquals(Source.DEFAULT, view.allowPlaintextRemoteOrigins().source());
        assertEquals("alice", view.updatedBy());
        assertNotNull(view.updatedAt());
        assertTrue(view.warnings().isEmpty(), view.warnings().toString());
    }

    @Test
    @DisplayName("a null field unsets the stored value, and the default takes over")
    void nullFieldUnsets() {
        store.put(new ConnectionSettings(true, "https://eddi.example.com", List.of("https://auth.atlassian.com"), true), "bob");
        var resource = resource(new ConnectionSettings());

        var view = resource.updateSettings(new ConnectionSettings(true, null, null, null));

        assertEquals(Source.DEFAULT, view.publicBaseUrl().source());
        assertEquals(Source.DEFAULT, view.credentialEndpointAllowlist().source());
        assertEquals(false, view.allowPlaintextRemoteOrigins().value());
        assertNull(store.current().getPublicBaseUrl());
    }

    @Test
    @DisplayName("a base URL a provider would not match is refused at the write boundary, naming the field")
    void malformedBaseUrlIsRefused() {
        var resource = resource(new ConnectionSettings());

        var failure = assertThrows(BadRequestException.class,
                () -> resource.updateSettings(new ConnectionSettings(true, "https://eddi.example.com/manage", null, null)));

        assertTrue(failure.getMessage().contains("publicBaseUrl"), failure.getMessage());
        assertTrue(failure.getMessage().contains("bare https origin"), failure.getMessage());
        assertNull(store.stored, "a refused write stores nothing");
    }

    @Test
    @DisplayName("a blank base URL is the same as leaving it unset")
    void blankBaseUrlUnsets() {
        var view = resource(new ConnectionSettings()).updateSettings(new ConnectionSettings(false, "   ", null, null));

        assertEquals(Source.DEFAULT, view.publicBaseUrl().source());
    }

    @Test
    @DisplayName("allowlist entries are canonicalised and de-duplicated before they are stored")
    void allowlistIsCanonicalised() {
        var view = resource(new ConnectionSettings()).updateSettings(new ConnectionSettings(true, null,
                List.of("https://auth.atlassian.com", " https://auth.atlassian.com ", "", "https://oauth2.googleapis.com"), null));

        assertEquals(List.of("https://auth.atlassian.com", "https://oauth2.googleapis.com"), view.credentialEndpointAllowlist().value());
        assertEquals(List.of("https://auth.atlassian.com", "https://oauth2.googleapis.com"), store.current().getCredentialEndpointAllowlist());
    }

    @Test
    @DisplayName("an allowlist entry with a path is refused, naming the field")
    void allowlistEntryWithPathIsRefused() {
        var resource = resource(new ConnectionSettings());

        var failure = assertThrows(BadRequestException.class, () -> resource
                .updateSettings(new ConnectionSettings(true, null, List.of("https://auth.atlassian.com/oauth/token"), null)));

        assertTrue(failure.getMessage().contains("credentialEndpointAllowlist"), failure.getMessage());
    }

    @Test
    @DisplayName("a remote plaintext allowlist entry is refused: no credential endpoint could ever use it")
    void remotePlaintextAllowlistEntryIsRefused() {
        var resource = resource(new ConnectionSettings());

        var failure = assertThrows(BadRequestException.class,
                () -> resource.updateSettings(new ConnectionSettings(true, null, List.of("http://auth.example.com"), null)));

        assertTrue(failure.getMessage().contains("http://auth.example.com"), failure.getMessage());
    }

    @Test
    @DisplayName("a loopback plaintext allowlist entry is accepted, as it is for a token URL")
    void loopbackPlaintextAllowlistEntryIsAccepted() {
        var view = resource(new ConnectionSettings()).updateSettings(new ConnectionSettings(true, null, List.of("http://localhost:8180"), null));

        assertEquals(List.of("http://localhost:8180"), view.credentialEndpointAllowlist().value());
    }

    @Test
    @DisplayName("a missing body is a 400, not a NullPointerException")
    void missingBodyIsRefused() {
        assertThrows(BadRequestException.class, () -> resource(new ConnectionSettings()).updateSettings(null));
    }

    @Test
    @DisplayName("a store that fails the write answers 500 and leaves the effective settings untouched")
    void failedWriteChangesNothing() {
        store.failWrites = true;
        var config = config(new ConnectionSettings());
        var resource = new RestConnectionSettings(config, store, identity);

        assertThrows(InternalServerErrorException.class, () -> resource.updateSettings(new ConnectionSettings(true, null, null, null)));

        assertFalse(config.isEnabled());
    }

    @Test
    @DisplayName("with authorization off the write is attributed to 'anonymous' rather than failing")
    void anonymousWriterIsRecorded() {
        when(identity.isAnonymous()).thenReturn(true);

        var view = resource(new ConnectionSettings()).updateSettings(new ConnectionSettings(false, null, null, null));

        assertEquals(RestConnectionSettings.ANONYMOUS, view.updatedBy());
    }

    // --- Pinning -------------------------------------------------------------

    @Test
    @DisplayName("changing a pinned field is a 409 naming the property, and nothing is stored")
    void changingAPinnedFieldIsAConflict() {
        var resource = resource(new ConnectionSettings(null, null, List.of("https://auth.atlassian.com"), null));

        var failure = assertThrows(ClientErrorException.class,
                () -> resource.updateSettings(new ConnectionSettings(true, null, List.of("https://evil.example.com"), null)));

        assertEquals(409, failure.getResponse().getStatus());
        assertTrue(failure.getMessage().contains(ConnectionsConfig.CREDENTIAL_ENDPOINT_ALLOWLIST), failure.getMessage());
        assertNull(store.stored);
    }

    @Test
    @DisplayName("restating a pinned value — in any order — is accepted, so a settings page can send back what it read")
    void restatingAPinnedValueIsAccepted() {
        var resource = resource(new ConnectionSettings(true, null, List.of("https://a.example.com", "https://b.example.com"), null));

        var view = resource.updateSettings(new ConnectionSettings(true, null, List.of("https://b.example.com", "https://a.example.com"), null));

        assertEquals(Source.PINNED, view.enabled().source());
        assertEquals(Source.PINNED, view.credentialEndpointAllowlist().source());
    }

    @Test
    @DisplayName("a pinned field is never seeded into the store, and a value stored before the pin is kept for when it goes")
    void pinnedFieldIsNeitherSeededNorErased() {
        store.put(new ConnectionSettings(false, "https://stored.example.com", null, null), "bob");
        var resource = resource(new ConnectionSettings(true, "https://pinned.example.com", null, null));

        resource.updateSettings(new ConnectionSettings(true, "https://pinned.example.com", null, true));

        ConnectionSettings stored = store.current();
        assertEquals(false, stored.getEnabled(), "the request's copy of the pinned value must not replace what was stored");
        assertEquals("https://stored.example.com", stored.getPublicBaseUrl());
        assertEquals(true, stored.getAllowPlaintextRemoteOrigins(), "unpinned fields are still written");
    }

    @Test
    @DisplayName("an effective value set by a property reads as PINNED")
    void pinnedValueReadsAsPinned() {
        var view = resource(new ConnectionSettings(null, "https://pinned.example.com", null, null)).readSettings();

        assertEquals(Source.PINNED, view.publicBaseUrl().source());
        assertEquals("https://pinned.example.com", view.publicBaseUrl().value());
    }

    // --- Warnings ------------------------------------------------------------

    @Test
    @DisplayName("enabled without a base URL or an allowlist says what will not work, without refusing the save")
    void incompleteEnablementIsWarned() {
        var view = resource(new ConnectionSettings()).updateSettings(new ConnectionSettings(true, null, null, null));

        assertEquals(2, view.warnings().size(), view.warnings().toString());
        assertTrue(view.warnings().stream().anyMatch(warning -> warning.contains("publicBaseUrl")), view.warnings().toString());
        assertTrue(view.warnings().stream().anyMatch(warning -> warning.contains("credentialEndpointAllowlist")), view.warnings().toString());
    }

    @Test
    @DisplayName("accepting plaintext remote origins is always called out")
    void plaintextIsWarned() {
        var view = resource(new ConnectionSettings()).updateSettings(new ConnectionSettings(false, null, null, true));

        assertTrue(view.warnings().stream().anyMatch(warning -> warning.contains("allowPlaintextRemoteOrigins")), view.warnings().toString());
    }

    private RestConnectionSettings resource(ConnectionSettings pinned) {
        return new RestConnectionSettings(config(pinned), store, identity);
    }

    private ConnectionsConfig config(ConnectionSettings pinned) {
        return new ConnectionsConfig(pinned, store, new AtomicLong()::get);
    }

    /** A real store, so a test can see exactly what a write left behind. */
    private static final class InMemorySettingsStore implements IConnectionSettingsStore {
        private final Map<String, StoredConnectionSettings> documents = new HashMap<>();
        StoredConnectionSettings stored;
        boolean failWrites;

        @Override
        public Optional<StoredConnectionSettings> read(String tenantId) {
            return Optional.ofNullable(documents.get(tenantId));
        }

        @Override
        public void write(String tenantId, StoredConnectionSettings settings) {
            if (failWrites) {
                throw new IllegalStateException("database down");
            }
            documents.put(tenantId, settings);
            stored = settings;
        }

        void put(ConnectionSettings settings, String by) {
            documents.put(ConnectionsConfig.TENANT, new StoredConnectionSettings(settings, null, by));
        }

        ConnectionSettings current() {
            return documents.get(ConnectionsConfig.TENANT).settings();
        }
    }
}
