/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.connections.ConnectionsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The retention sweep for in-flight authorization states.
 * <p>
 * {@code deleteExpired()} shipped with no caller. On MongoDB a TTL index hid
 * that; on PostgreSQL nothing did, so every abandoned consent screen left a row
 * holding a live PKCE code verifier behind forever. The test that matters is
 * therefore the boring one: that something actually calls it.
 */
class OAuthStateMaintenanceTest {

    private static final ConnectionsConfig ENABLED = new ConnectionsConfig(true, Optional.of("https://eddi.example.com"));
    private static final ConnectionsConfig DISABLED = new ConnectionsConfig(false, Optional.of("https://eddi.example.com"));

    @Test
    @DisplayName("the sweep deletes expired states while the feature is on")
    void sweepDeletesExpiredStates() {
        IOAuthStateStore stateStore = mock(IOAuthStateStore.class);
        doReturn(4).when(stateStore).deleteExpired();

        new OAuthStateMaintenance(stateStore, ENABLED).sweepExpiredStates();

        verify(stateStore).deleteExpired();
    }

    @Test
    @DisplayName("the sweep touches nothing while the feature is off")
    void sweepIsANoOpWhenDisabled() {
        // A scheduled job runs whether or not the feature it serves is enabled, and a
        // deployment that never turned connections on has no table for this to sweep.
        // The paired test above is what makes this one mean something: the same store,
        // the same call, and the only difference is the config.
        IOAuthStateStore stateStore = mock(IOAuthStateStore.class);

        new OAuthStateMaintenance(stateStore, DISABLED).sweepExpiredStates();

        verifyNoInteractions(stateStore);
    }

    @Test
    @DisplayName("a store failure is swallowed, so one bad sweep does not kill the schedule")
    void storeFailureDoesNotEscape() {
        // This is retention, not enforcement: claim() checks expiry itself, so the
        // rows are unusable whether or not they are ever deleted. An exception
        // escaping a @Scheduled method is logged as an error by the scheduler and
        // buys nothing in exchange.
        IOAuthStateStore stateStore = mock(IOAuthStateStore.class);
        doThrow(new IllegalStateException("the database is unreachable")).when(stateStore).deleteExpired();
        var maintenance = new OAuthStateMaintenance(stateStore, ENABLED);

        assertDoesNotThrow(maintenance::sweepExpiredStates,
                "a failed retention sweep must stay a log line; the next hourly run retries and nothing was depending on this one");
        verify(stateStore).deleteExpired();
    }
}
