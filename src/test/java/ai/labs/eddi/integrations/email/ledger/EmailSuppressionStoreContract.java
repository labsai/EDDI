/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour both suppression stores must share, run against a real database
 * by {@link MongoEmailSuppressionStoreIT} and
 * {@link PostgresEmailSuppressionStoreIT}, and against the in-memory double by
 * {@link InMemoryEmailSuppressionStoreTest}.
 */
interface EmailSuppressionStoreContract {

    IEmailSuppressionStore store();

    void clear() throws Exception;

    /**
     * Every value the store holds, as text — to prove no address is among them.
     */
    Set<String> storedValues() throws Exception;

    Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    default void reset() throws Exception {
        clear();
    }

    @Test
    @DisplayName("suppressing is idempotent and per integration; clearing restores sending")
    default void suppressAndClear() {
        assertTrue(store().suppress("int-1", "alice@example.com", "bounce:5.1.1", NOW));
        assertFalse(store().suppress("int-1", "alice@example.com", "bounce:5.1.1", NOW), "already suppressed");
        assertTrue(store().isSuppressed("int-1", "alice@example.com"));
        assertFalse(store().isSuppressed("int-2", "alice@example.com"), "another integration may still reply");
        assertFalse(store().isSuppressed("int-1", "bob@example.com"));

        assertTrue(store().remove("int-1", "alice@example.com"));
        assertFalse(store().remove("int-1", "alice@example.com"));
        assertFalse(store().isSuppressed("int-1", "alice@example.com"));
    }

    @Test
    @DisplayName("a listing shows hash, domain, reason and date, newest first; an integration's delete removes its list")
    default void listAndRemoveAll() {
        store().suppress("int-1", "alice@example.com", "bounce:5.1.1", NOW.minusSeconds(60));
        store().suppress("int-1", "bob@example.org", "bounce:5.2.1", NOW);
        store().suppress("int-2", "alice@example.com", "bounce:5.1.1", NOW);

        var listed = store().list("int-1", 10);
        assertEquals(2, listed.size());
        assertEquals(IEmailSuppressionStore.hash("bob@example.org"), listed.get(0).addressHash());
        assertEquals("example.org", listed.get(0).domain());
        assertEquals("bounce:5.2.1", listed.get(0).reason());
        assertEquals(NOW, listed.get(0).createdAt());
        assertEquals(1, store().list("int-1", 1).size());

        assertEquals(2, store().removeAll("int-1"));
        assertTrue(store().list("int-1", 10).isEmpty());
        assertTrue(store().isSuppressed("int-2", "alice@example.com"));
    }

    @Test
    @DisplayName("GDPR: an address's entries are found and erased across integrations; no address is stored")
    default void gdpr() throws Exception {
        store().suppress("int-1", "alice@example.com", "bounce:5.1.1", NOW);
        store().suppress("int-2", "alice@example.com", "bounce:5.1.1", NOW);
        store().suppress("int-2", "bob@example.com", "bounce:5.1.1", NOW);

        assertEquals(2, store().findByAddress("alice@example.com").size());
        assertTrue(storedValues().stream().noneMatch(v -> v.contains("alice@") || v.contains("bob@")), "only hashes and domains");
        assertEquals(2, store().deleteByAddress("alice@example.com"));
        assertTrue(store().findByAddress("alice@example.com").isEmpty());
        assertTrue(store().isSuppressed("int-2", "bob@example.com"));
    }
}
