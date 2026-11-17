/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.Kind;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.PendingRemoval;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every {@link IErasureTombstoneStore} must show, run against
 * MongoDB and PostgreSQL by their integration tests.
 */
public interface ErasureTombstoneStoreContract {

    IErasureTombstoneStore tombstoneStore();

    /**
     * Far ahead of the database's clock, so the erasedAt a test writes is the later
     * of the two (see {@code erasedAt}).
     */
    Instant T0 = Instant.parse("2100-01-01T10:00:00Z");

    @Test
    default void tombstonesAreReadUntilTheyExpireAndOnlyEverMoveLater() {
        IErasureTombstoneStore store = tombstoneStore();
        Instant expires = T0.plus(Duration.ofDays(400));
        store.putTombstones(Kind.USER, List.of("k1"), T0, expires);
        store.putTombstones(Kind.CONVERSATION, List.of("c1", "c2"), T0, expires);

        assertEquals(Optional.of(T0), store.erasedAt(Kind.USER, "k1", T0));
        assertEquals(Optional.of(T0), store.erasedAt(Kind.CONVERSATION, "c2", T0));
        assertTrue(store.erasedAt(Kind.CONVERSATION, "k1", T0).isEmpty(), "the kind is part of the key");
        assertTrue(store.erasedAt(Kind.USER, "nobody", T0).isEmpty());

        // Idempotent re-run: later wins, an earlier write never moves it back.
        Instant later = T0.plus(Duration.ofHours(1));
        store.putTombstones(Kind.USER, List.of("k1"), later, expires.plus(Duration.ofHours(1)));
        store.putTombstones(Kind.USER, List.of("k1"), T0, expires);
        assertEquals(Optional.of(later), store.erasedAt(Kind.USER, "k1", T0));

        assertTrue(store.erasedAt(Kind.CONVERSATION, "c1", expires).isEmpty(), "expired: ignored before it is deleted");
        assertEquals(2, store.deleteExpiredTombstones(expires));
        assertTrue(store.erasedAt(Kind.USER, "k1", T0).isPresent(), "k1 expires an hour later");
        store.putTombstones(Kind.USER, List.of(), T0, expires);
    }

    @Test
    default void aMetaValueIsInsertedOnceAndThenAdopted() {
        IErasureTombstoneStore store = tombstoneStore();
        String first = store.putMetaIfAbsent("tombstone-salt", "c2FsdC1vbmU=");
        assertEquals("c2FsdC1vbmU=", first);
        assertEquals(first, store.putMetaIfAbsent("tombstone-salt", "c2FsdC10d28="), "every later caller adopts the stored value");
        assertEquals("salt:abc", store.putMetaIfAbsent("tombstone-key-check", "salt:abc"), "names are independent");
    }

    @Test
    default void aTombstoneFromASlowClockCountsFromTheDatabaseWriteTime() {
        IErasureTombstoneStore store = tombstoneStore();
        Instant before = Instant.now().minus(Duration.ofMinutes(1));
        Instant slowNode = Instant.parse("2020-01-01T00:00:00Z");
        store.putTombstones(Kind.CONVERSATION, List.of("slow"), slowNode, T0);
        Instant effective = store.erasedAt(Kind.CONVERSATION, "slow", Instant.now()).orElseThrow();
        assertTrue(effective.isAfter(before), "the database's clock, not the node's 2020: " + effective);
    }

    @Test
    default void pendingRemovalsAreFilteredByWriterUserAndConversation() {
        IErasureTombstoneStore store = tombstoneStore();
        store.addPendingRemoval(new PendingRemoval("alerts", "r1", "u1", "c1", T0));
        store.addPendingRemoval(new PendingRemoval("alerts", "r1", "u1", "c1", T0.plusSeconds(9)));
        store.addPendingRemoval(new PendingRemoval("alerts", "r2", null, "c2", T0.plusSeconds(1)));
        store.addPendingRemoval(new PendingRemoval("health", "r3", "u1", null, T0.plusSeconds(2)));

        assertEquals(3, store.countPendingRemovals(null, null, null), "a second record of the same row is a no-op");
        assertEquals(List.of("r1", "r2", "r3"), store.pendingRemovals(null, null, null, 10).stream().map(PendingRemoval::rowId).toList());
        assertEquals(2, store.countPendingRemovals(null, "u1", null));
        assertEquals(1, store.countPendingRemovals("alerts", "u1", null));
        assertEquals(2, store.countPendingRemovals("alerts", "u1", "c2"), "user OR conversation");
        assertEquals(1, store.countPendingRemovals(null, null, "c2"));
        assertEquals(1, store.pendingRemovals(null, null, null, 1).size());

        PendingRemoval read = store.pendingRemovals("alerts", null, "c1", 10).getFirst();
        assertEquals(new PendingRemoval("alerts", "r1", "u1", "c1", T0), read, "the first record wins");

        assertTrue(store.removePendingRemoval("alerts", "r1"));
        assertFalse(store.removePendingRemoval("alerts", "r1"));
        assertEquals(1, store.countPendingRemovals(null, "u1", "c1"), "only health's r3 remains for u1");
    }
}
