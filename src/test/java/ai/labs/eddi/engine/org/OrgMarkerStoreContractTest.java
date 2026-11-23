/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every {@link IOrgMarkerStore} must share, run against a real
 * MongoDB and a real PostgreSQL by the two subclasses.
 */
abstract class OrgMarkerStoreContractTest {

    /** A store over an empty, freshly created database. */
    abstract IOrgMarkerStore emptyStore();

    /** True when the record's collection or table exists in the database. */
    abstract boolean storageExists();

    @Test
    void aFreshDatabaseReadsAsNoRecordAndTheReadCreatesNothing() {
        IOrgMarkerStore store = emptyStore();

        assertEquals(OrgMarker.NONE, store.read());
        assertFalse(storageExists(), "a read must not create the collection or table: it runs before anything may write");
    }

    @Test
    void theMarkerMovesForwardAtomically() {
        IOrgMarkerStore store = emptyStore();

        assertTrue(store.moveMarker(OrgMarkerState.ABSENT, OrgMarkerState.SWITCHING));
        assertEquals(OrgMarkerState.SWITCHING, store.read().state());
        assertFalse(store.moveMarker(OrgMarkerState.ABSENT, OrgMarkerState.SWITCHING), "a stale expectation must not move it");
        assertTrue(store.moveMarker(OrgMarkerState.SWITCHING, OrgMarkerState.ENABLED));

        OrgMarker enabled = store.read();
        assertEquals(OrgMarkerState.ENABLED, enabled.state());
        assertNotNull(enabled.updatedAt());
        assertTrue(enabled.updatedAt().isAfter(Instant.now().minusSeconds(300)));
    }

    @Test
    void aBackwardMoveIsRefusedBeforeTouchingTheDatabase() {
        IOrgMarkerStore store = emptyStore();

        assertThrows(IllegalArgumentException.class, () -> store.moveMarker(OrgMarkerState.ENABLED, OrgMarkerState.ABSENT));
        assertThrows(IllegalArgumentException.class, () -> store.moveMarker(OrgMarkerState.SWITCHING, OrgMarkerState.ABSENT));
        assertFalse(storageExists());
    }

    @Test
    void theRequiredLayoutOnlyRises() {
        IOrgMarkerStore store = emptyStore();

        assertEquals(1, store.raiseRequiredLayout(1));
        assertEquals(3, store.raiseRequiredLayout(3));
        assertEquals(3, store.raiseRequiredLayout(2), "a lower level must leave it unchanged");
        assertEquals(3, store.raiseRequiredLayout(0));
        assertEquals(3, store.read().requiredLayout());
        assertEquals(OrgMarkerState.ABSENT, store.read().state(), "raising the layout does not move the marker");
        assertThrows(IllegalArgumentException.class, () -> store.raiseRequiredLayout(-1));
    }

    /** Writes a record whose marker state no release knows, bypassing the store. */
    abstract void storeUnknownState(String state);

    @Test
    void aFreshInstallMayGoStraightToEnabled() {
        IOrgMarkerStore store = emptyStore();

        assertTrue(store.moveMarker(OrgMarkerState.ABSENT, OrgMarkerState.ENABLED));
        assertEquals(OrgMarkerState.ENABLED, store.read().state());
    }

    @Test
    void anUnknownStoredStateIsUnsupportedNotRetried() {
        IOrgMarkerStore store = emptyStore();
        store.raiseRequiredLayout(0);
        storeUnknownState("FUTURE");

        assertThrows(UnsupportedLayoutRecordException.class, store::read);
    }

    @Test
    void theGuardRefusesARaisedLayoutReadFromTheRealStore() {
        IOrgMarkerStore store = emptyStore();
        DataLayoutGuard guard = new DataLayoutGuard(store, Duration.ZERO);

        guard.check();
        store.raiseRequiredLayout(DataLayoutGuard.SUPPORTED_LAYOUT + 1);

        assertThrows(IllegalStateException.class, guard::check);
    }
}
