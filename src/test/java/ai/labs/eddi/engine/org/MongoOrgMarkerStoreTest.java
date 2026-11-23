/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrgMarkerStoreContractTest} against a real MongoDB, plus the
 * BSON-typing cases only it has.
 */
class MongoOrgMarkerStoreTest extends OrgMarkerStoreContractTest {

    /** The tag docker-compose.yml and the ITs pin. */
    @SuppressWarnings("resource")
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0.43");
    private static MongoClient client;
    private static MongoDatabase database;

    @BeforeAll
    static void start() {
        MONGO.start();
        client = MongoClients.create(MONGO.getConnectionString());
        database = client.getDatabase("org_marker_test");
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        MONGO.stop();
    }

    @Override
    IOrgMarkerStore emptyStore() {
        database.getCollection(MongoOrgMarkerStore.COLLECTION).drop();
        return new MongoOrgMarkerStore(database);
    }

    @Override
    boolean storageExists() {
        return database.listCollectionNames().into(new ArrayList<>()).contains(MongoOrgMarkerStore.COLLECTION);
    }

    @Override
    void storeUnknownState(String state) {
        raw().updateOne(Filters.eq("_id", MongoOrgMarkerStore.RECORD_ID), Updates.set(MongoOrgMarkerStore.MARKER_STATE, state));
    }

    private MongoCollection<Document> raw() {
        return database.getCollection(MongoOrgMarkerStore.COLLECTION);
    }

    @Test
    void aLevelStoredAsLongOrWholeDoubleReadsExactly() {
        IOrgMarkerStore store = emptyStore();
        raw().insertOne(new Document("_id", MongoOrgMarkerStore.RECORD_ID).append(MongoOrgMarkerStore.REQUIRED_LAYOUT, 2L));
        assertEquals(2, store.read().requiredLayout());

        raw().updateOne(Filters.eq("_id", MongoOrgMarkerStore.RECORD_ID), Updates.set(MongoOrgMarkerStore.REQUIRED_LAYOUT, 3.0d));
        assertEquals(3, store.read().requiredLayout());
    }

    @Test
    void aLevelThatWouldTruncateIsUnsupportedNotZero() {
        IOrgMarkerStore store = emptyStore();
        raw().insertOne(new Document("_id", MongoOrgMarkerStore.RECORD_ID).append(MongoOrgMarkerStore.REQUIRED_LAYOUT, 4_294_967_296L));
        assertThrows(UnsupportedLayoutRecordException.class, store::read);

        raw().updateOne(Filters.eq("_id", MongoOrgMarkerStore.RECORD_ID), Updates.set(MongoOrgMarkerStore.REQUIRED_LAYOUT, 0.9d));
        assertThrows(UnsupportedLayoutRecordException.class, store::read);
    }

    @Test
    void aRecordWithMissingFieldsCanStillBeMovedAndRaised() {
        IOrgMarkerStore store = emptyStore();
        raw().insertOne(new Document("_id", MongoOrgMarkerStore.RECORD_ID));
        assertEquals(OrgMarker.NONE.state(), store.read().state());
        assertEquals(0, store.read().requiredLayout());

        assertEquals(1, store.raiseRequiredLayout(1), "a missing level reads as 0 and must be raisable");
        assertTrue(store.moveMarker(OrgMarkerState.ABSENT, OrgMarkerState.SWITCHING), "a missing state reads as ABSENT and must move");
    }
}
