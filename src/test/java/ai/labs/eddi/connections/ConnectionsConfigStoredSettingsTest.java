/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.connections.settings.ConnectionSettings;
import ai.labs.eddi.connections.settings.IConnectionSettingsStore;
import ai.labs.eddi.connections.settings.IConnectionSettingsStore.StoredConnectionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How {@link ConnectionsConfig} resolves a setting now that it can change at
 * runtime: pinned beats stored beats default, stored values are cached for a
 * bounded time, and a store that cannot be read fails closed without forgetting
 * what it last knew.
 */
class ConnectionsConfigStoredSettingsTest {

    private static final long TTL_NANOS = ConnectionsConfig.STORED_SETTINGS_TTL.toNanos();

    private IConnectionSettingsStore store;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        store = mock(IConnectionSettingsStore.class);
        clock = new AtomicLong(1_000L);
    }

    @Test
    @DisplayName("with nothing pinned and nothing stored, every setting fails closed")
    void defaultsFailClosed() {
        when(store.read(anyString())).thenReturn(Optional.empty());
        var config = config(new ConnectionSettings());

        assertFalse(config.isEnabled());
        assertEquals("", config.getPublicBaseUrl());
        assertTrue(config.credentialEndpointOrigins().isEmpty());
        assertFalse(config.isAllowPlaintextRemoteOrigins());
        assertTrue(config.publicBaseUrlProblem().isPresent(), "no base URL is a problem the authorize route must be able to name");
    }

    @Test
    @DisplayName("stored values apply without a restart")
    void storedValuesApply() {
        storeReturns(new ConnectionSettings(true, "https://eddi.example.com", List.of("https://auth.atlassian.com"), true));
        var config = config(new ConnectionSettings());

        assertTrue(config.isEnabled());
        assertEquals("https://eddi.example.com", config.getPublicBaseUrl());
        assertEquals(Set.of("https://auth.atlassian.com"), config.credentialEndpointOrigins());
        assertTrue(config.isAllowPlaintextRemoteOrigins());
        assertEquals("https://eddi.example.com/connections/callback", config.redirectUri());
        assertTrue(config.publicBaseUrlProblem().isEmpty());
    }

    @Test
    @DisplayName("a pinned value wins over the stored one, field by field")
    void pinnedWinsOverStored() {
        storeReturns(new ConnectionSettings(true, "https://stored.example.com", List.of("https://stored-auth.example.com"), true));
        var config = config(new ConnectionSettings(false, "https://pinned.example.com", List.of("https://pinned-auth.example.com"), null));

        assertFalse(config.isEnabled(), "a pinned false must not be overridden by a stored true");
        assertEquals("https://pinned.example.com", config.getPublicBaseUrl());
        assertEquals(Set.of("https://pinned-auth.example.com"), config.credentialEndpointOrigins());
        assertTrue(config.isAllowPlaintextRemoteOrigins(), "an unpinned field still comes from the store");
    }

    @Test
    @DisplayName("a fully pinned configuration never reads the store for pinned fields")
    void fullyPinnedValuesAreStable() {
        var config = config(new ConnectionSettings(true, "https://eddi.example.com", List.of("https://auth.atlassian.com"), false));

        assertEquals(Set.of("https://auth.atlassian.com"), config.credentialEndpointOrigins());
        verify(store, never()).read(anyString());
    }

    @Test
    @DisplayName("stored values are cached for the TTL and read again once it expires")
    void storedValuesAreCachedForTheTtl() {
        storeReturns(new ConnectionSettings(false, null, null, null));
        var config = config(new ConnectionSettings());

        assertFalse(config.isEnabled());
        storeReturns(new ConnectionSettings(true, null, null, null));
        clock.addAndGet(TTL_NANOS - 1);
        assertFalse(config.isEnabled(), "within the TTL the cached value is served");
        verify(store, times(1)).read(ConnectionsConfig.TENANT);

        clock.addAndGet(2);
        assertTrue(config.isEnabled(), "past the TTL another replica's write must be picked up");
        verify(store, times(2)).read(ConnectionsConfig.TENANT);
    }

    @Test
    @DisplayName("a document this instance wrote is served at once, without reading it back")
    void adoptedDocumentIsServedImmediately() {
        storeReturns(new ConnectionSettings(false, null, null, null));
        var config = config(new ConnectionSettings());
        assertFalse(config.isEnabled());

        config.adopt(new StoredConnectionSettings(new ConnectionSettings(true, null, List.of("https://auth.atlassian.com"), null), Instant.now(),
                "admin"));

        assertTrue(config.isEnabled());
        assertEquals(Set.of("https://auth.atlassian.com"), config.credentialEndpointOrigins());
        verify(store, times(1)).read(ConnectionsConfig.TENANT);
    }

    @Test
    @DisplayName("refresh re-reads the store inside the TTL")
    void refreshReadsNow() {
        storeReturns(new ConnectionSettings(false, null, null, null));
        var config = config(new ConnectionSettings());
        assertFalse(config.isEnabled());

        storeReturns(new ConnectionSettings(true, null, null, null));
        config.refresh();

        assertTrue(config.isEnabled());
    }

    @Test
    @DisplayName("a store that cannot be read before the first read leaves only pinned values and defaults")
    void unreadableStoreBeforeFirstReadFailsClosed() {
        when(store.read(anyString())).thenThrow(new IllegalStateException("database down"));
        var config = config(new ConnectionSettings());

        assertFalse(config.isEnabled());
        assertTrue(config.credentialEndpointOrigins().isEmpty());
    }

    @Test
    @DisplayName("a store that stops answering keeps the values last read rather than switching the feature off mid-flight")
    void unreadableStoreAfterAReadKeepsPreviousValues() {
        storeReturns(new ConnectionSettings(true, null, List.of("https://auth.atlassian.com"), null));
        var config = config(new ConnectionSettings());
        assertTrue(config.isEnabled());

        when(store.read(anyString())).thenThrow(new IllegalStateException("database down"));
        clock.addAndGet(TTL_NANOS + 1);

        assertTrue(config.isEnabled());
        assertEquals(Set.of("https://auth.atlassian.com"), config.credentialEndpointOrigins());
    }

    @Test
    @DisplayName("a stored allowlist entry that is not a bare origin is dropped, and the rest still approve")
    void malformedStoredOriginIsDropped() {
        storeReturns(new ConnectionSettings(true, null, List.of("https://auth.atlassian.com/oauth/token", "https://oauth2.googleapis.com", " "),
                null));
        var config = config(new ConnectionSettings());

        assertEquals(Set.of("https://oauth2.googleapis.com"), config.credentialEndpointOrigins());
    }

    @Test
    @DisplayName("a pinned allowlist entry that is not a bare origin is refused when the bean is built")
    void malformedPinnedOriginIsRefused() {
        var pinned = new ConnectionSettings(null, null, List.of("https://auth.atlassian.com/oauth/token"), null);

        var failure = assertThrows(IllegalArgumentException.class, () -> config(pinned));

        assertTrue(failure.getMessage().contains(ConnectionsConfig.CREDENTIAL_ENDPOINT_ALLOWLIST), failure.getMessage());
    }

    @Test
    @DisplayName("a property of nothing but separators pins nothing")
    void separatorOnlyPropertyPinsNothing() {
        storeReturns(new ConnectionSettings(null, null, List.of("https://auth.atlassian.com"), null));
        var config = new ConnectionsConfig(Optional.empty(), Optional.of("  "), Optional.of(" , "), Optional.empty(), store);

        assertNull(config.pinnedSettings().getCredentialEndpointAllowlist());
        assertNull(config.pinnedSettings().getPublicBaseUrl());
        assertEquals(Set.of("https://auth.atlassian.com"), config.credentialEndpointOrigins());
    }

    @Test
    @DisplayName("the pinned copy handed out cannot be used to change what is pinned")
    void pinnedSettingsAreACopy() {
        var config = config(new ConnectionSettings(true, null, null, null));

        config.pinnedSettings().setEnabled(false);

        assertTrue(config.pinnedSettings().getEnabled());
    }

    @Test
    @DisplayName("an error naming a setting names both the settings endpoint and the property")
    void describeNamesBothHandles() {
        String described = ConnectionsConfig.describe("publicBaseUrl", ConnectionsConfig.PUBLIC_BASE_URL);

        assertTrue(described.contains("publicBaseUrl"));
        assertTrue(described.contains(ConnectionsConfig.SETTINGS_PATH));
        assertTrue(described.contains(ConnectionsConfig.PUBLIC_BASE_URL));
    }

    private ConnectionsConfig config(ConnectionSettings pinned) {
        return new ConnectionsConfig(pinned, store, clock::get);
    }

    private void storeReturns(ConnectionSettings settings) {
        when(store.read(anyString())).thenReturn(Optional.of(new StoredConnectionSettings(settings, Instant.now(), "admin")));
    }
}
