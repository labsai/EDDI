/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.gdpr.erasure.ErasureTombstoneStoreContract;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.Kind;
import ai.labs.eddi.engine.gdpr.erasure.mongo.ErasureTombstoneStore;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Erasure tombstone store (MongoDB)")
class MongoErasureTombstoneStoreIT extends MongoTestBase implements ErasureTombstoneStoreContract {

    private ErasureTombstoneStore store;

    @BeforeEach
    void setUp() {
        dropCollections("erasuretombstones", "erasurependingremovals", "erasuremeta");
        store = new ErasureTombstoneStore(getDatabase());
    }

    @Override
    public IErasureTombstoneStore tombstoneStore() {
        return store;
    }

    /**
     * The client reads {@code nearest()}; a tombstone or pending-removal read from
     * a lagging secondary would break the guarantee, so every collection of the
     * store reads the primary with majority concern and writes with majority.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyCollectionReadsThePrimaryWithMajorityConcern() throws Exception {
        for (String name : List.of("tombstones", "pending", "meta")) {
            Field field = ErasureTombstoneStore.class.getDeclaredField(name);
            field.setAccessible(true);
            MongoCollection<Document> collection = (MongoCollection<Document>) field.get(store);
            assertEquals(ReadPreference.primary(), collection.getReadPreference(), name);
            assertEquals(ReadConcern.MAJORITY, collection.getReadConcern(), name);
            assertEquals(WriteConcern.MAJORITY, collection.getWriteConcern(), name);
        }
    }

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> field(ErasureTombstoneStore store, String name) throws Exception {
        Field field = ErasureTombstoneStore.class.getDeclaredField(name);
        field.setAccessible(true);
        return (MongoCollection<Document>) field.get(store);
    }

    /**
     * The shared test container is a standalone server, which refuses linearizable
     * reads: the point reads stay majority reads there, and every contract case
     * above ran through them.
     */
    @Test
    void thePointReadsStayMajorityReadsOnAStandaloneServer() throws Exception {
        for (String name : List.of("tombstoneReads", "metaReads")) {
            assertEquals(ReadPreference.primary(), field(store, name).getReadPreference(), name);
            assertEquals(ReadConcern.MAJORITY, field(store, name).getReadConcern(), name);
        }
    }

    /**
     * On a replica set the tombstone and meta point reads are linearizable, and the
     * server accepts them as issued (by {@code _id}, with a {@code maxTime}). Only
     * the configuration and the round trip are checked; a deposed primary cannot be
     * reproduced on one node.
     */
    @Test
    void thePointReadsAreLinearizableOnAReplicaSet() throws Exception {
        try (MongoDBContainer replicaSet = new MongoDBContainer("mongo:6.0").withReplicaSet()) {
            replicaSet.start();
            try (MongoClient client = MongoClients.create(replicaSet.getReplicaSetUrl())) {
                ErasureTombstoneStore replicated = new ErasureTombstoneStore(client.getDatabase("eddi_rs_test"));
                for (String name : List.of("tombstoneReads", "metaReads")) {
                    assertEquals(ReadPreference.primary(), field(replicated, name).getReadPreference(), name);
                    assertEquals(ReadConcern.LINEARIZABLE, field(replicated, name).getReadConcern(), name);
                }
                assertEquals(ReadConcern.MAJORITY, field(replicated, "pending").getReadConcern(),
                        "the multi-document pending queries stay majority reads");
                Instant now = Instant.now();
                replicated.putTombstones(Kind.USER, List.of("rs-key"), now, now.plus(Duration.ofDays(1)));
                assertTrue(replicated.erasedAt(Kind.USER, "rs-key", now).isPresent());
                assertTrue(replicated.erasedAt(Kind.USER, "rs-key", now.plus(Duration.ofDays(2))).isEmpty(), "expired");
                assertEquals("v1", replicated.putMetaIfAbsent("m", "v1"));
                assertEquals("v1", replicated.meta("m").orElseThrow());
            }
        }
    }

    /**
     * The write time only moves later: a write on a primary whose clock is behind
     * the one that wrote the tombstone keeps the stored, later write time.
     */
    @Test
    void anEarlierClockNeverMovesTheWriteTimeBack() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        Instant expires = Instant.now().plus(Duration.ofDays(400));
        store.putTombstones(Kind.USER, List.of("ahead"), past, expires);
        Instant ahead = Instant.now().plus(Duration.ofHours(1));
        getDatabase().getCollection("erasuretombstones").updateOne(new Document("_id", "USER:ahead"),
                new Document("$set", new Document("writtenAt", Date.from(ahead))));

        store.putTombstones(Kind.USER, List.of("ahead"), past, expires);

        Instant after = store.erasedAt(Kind.USER, "ahead", Instant.now()).orElseThrow();
        assertEquals(ahead.toEpochMilli(), after.toEpochMilli(), "the cutoff moved back");
    }

    @Test
    void createsTheTtlAndLookupIndexesIdempotently() {
        new ErasureTombstoneStore(getDatabase()); // the DDL runs on every start
        List<Document> tombstoneIndexes = getDatabase().getCollection("erasuretombstones").listIndexes().into(new ArrayList<>());
        Document ttl = tombstoneIndexes.stream().filter(i -> "idx_erasuretombstone_ttl".equals(i.getString("name"))).findFirst().orElseThrow();
        assertEquals(0L, ((Number) ttl.get("expireAfterSeconds")).longValue());
        List<String> pending = getDatabase().getCollection("erasurependingremovals").listIndexes().into(new ArrayList<>()).stream()
                .map(i -> i.getString("name")).toList();
        assertTrue(pending.containsAll(List.of("idx_erasurepending_user", "idx_erasurepending_conversation", "idx_erasurepending_created")),
                pending.toString());
    }
}
