/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.hitl.tools.mongo.HitlToolJournalStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The journal's TTL index against a real MongoDB: an unfiltered index that an
 * old pod recreates during a rolling update is found and dropped, and the
 * partial one is (re)established.
 */
@DisplayName("HitlToolJournalStore TTL index self-healing (MongoDB)")
class HitlToolJournalStoreIndexIT extends MongoTestBase {

    private static final String COLLECTION = "hitltoolexecutionjournal";

    private HitlToolJournalStore store;
    private MongoCollection<Document> journal;

    @BeforeEach
    void setUp() {
        dropCollections(COLLECTION);
        store = new HitlToolJournalStore(getDatabase(), Duration.ofDays(30));
        journal = getDatabase().getCollection(COLLECTION);
    }

    private Document index(String name) {
        List<Document> indexes = journal.listIndexes().into(new ArrayList<>());
        return indexes.stream().filter(i -> name.equals(i.getString("name"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("a fresh store has the partial TTL index and nothing to repair")
    void freshStoreHasThePartialIndex() {
        Document ttl = index("idx_journal_ttl_completed");
        assertNotNull(ttl);
        assertNotNull(ttl.get("partialFilterExpression"));
        assertEquals(0, store.reconcileRetention());
    }

    @Test
    @DisplayName("an unfiltered claimedAt TTL index recreated by an old pod is detected and dropped")
    void dropsTheRecreatedUnfilteredIndex() {
        // What an old pod's constructor does after the new pod removed it.
        journal.createIndex(Indexes.ascending("claimedAt"),
                new IndexOptions().name("idx_journal_ttl_claimed").expireAfter(Duration.ofDays(30).toSeconds(), TimeUnit.SECONDS));
        assertNotNull(index("idx_journal_ttl_claimed"));

        assertEquals(1, store.reconcileRetention());

        assertNull(index("idx_journal_ttl_claimed"), "the unfiltered TTL must be gone");
        assertNotNull(index("idx_journal_ttl_completed"), "the partial TTL must remain");
        assertEquals(0, store.reconcileRetention(), "a second pass finds nothing");
    }

    @Test
    @DisplayName("a missing partial index is recreated")
    void recreatesTheMissingPartialIndex() {
        journal.dropIndex("idx_journal_ttl_completed");

        store.reconcileRetention();

        assertNotNull(index("idx_journal_ttl_completed"));
    }
}
