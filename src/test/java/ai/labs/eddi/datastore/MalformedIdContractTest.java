/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresIds;
import ai.labs.eddi.datastore.postgres.PostgresResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.enterprise.inject.Instance;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * One id contract for both datastores: <b>an id the backend cannot store is an
 * unknown id</b>. Every lookup answers "not found", every write or delete of it
 * is a no-op, and no query is sent — so the REST layer answers 404 for it, as
 * it does for a well-formed id that does not exist.
 *
 * <p>
 * Before, the same request split three ways: PostgreSQL bound a non-UUID into
 * {@code ?::uuid}, got SQLSTATE 22P02 and answered 500 (seven endpoints, live:
 * {@code GET /rulestore/rulesets/xyz},
 * {@code GET /agents/000000000000000000000000}, {@code POST /agents/xyz}, …);
 * MongoDB's {@code new ObjectId(id)} threw {@link IllegalArgumentException},
 * which answered 400 on a GET and 500 on a POST — including for a UUID, a
 * perfectly good id on the other backend.
 * </p>
 *
 * <p>
 * The same malformed ids are run through every implementation, so a store that
 * drifts from the contract fails here rather than on one backend in production.
 * Each backend's own native id is malformed for the other one.
 * </p>
 */
@DisplayName("Datastore id contract — an id a backend cannot store names nothing")
class MalformedIdContractTest {

    /** Malformed on both backends. */
    private static final String[] MALFORMED_EVERYWHERE = {"xyz", "------------------", "", "0000000000000000000000000000000000000000"};

    private static final String OBJECT_ID = "000000000000000000000000";
    private static final String UUID = "00000000-0000-4000-8000-000000000000";

    @Nested
    @DisplayName("PostgreSQL")
    class Postgres {

        private final DataSource dataSource = mock(DataSource.class);
        private final Connection connection = mock(Connection.class);

        Postgres() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(mock(Statement.class));
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {"xyz", "------------------", "", "0000000000000000000000000000000000000000", OBJECT_ID})
        @DisplayName("resource storage: not found, no statement")
        void resourceStorage(String id) throws Exception {
            var storage = new PostgresResourceStorage<>(dataSource, "rulesets", mock(IJsonSerialization.class), Object.class);
            clearInvocations(connection);

            assertNull(storage.read(id, 1));
            assertNull(storage.readHistory(id, 1));
            assertNull(storage.readHistoryLatest(id));
            assertEquals(-1, storage.getCurrentVersion(id));
            assertTrue(storage.readMany(List.of(idAt(id, 1))).isEmpty());
            storage.remove(id);
            storage.removeAllPermanently(id);

            verify(connection, never()).prepareStatement(anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {"xyz", "------------------", "", "0000000000000000000000000000000000000000", OBJECT_ID})
        @DisplayName("conversation memory: not found, no statement")
        @SuppressWarnings("unchecked")
        void conversationMemory(String id) throws Exception {
            Instance<DataSource> instance = mock(Instance.class);
            when(instance.get()).thenReturn(dataSource);
            var store = new PostgresConversationMemoryStore(instance, mock(IJsonSerialization.class));
            clearInvocations(connection);

            assertConversationIdIsUnknown(store, id);

            verify(connection, never()).prepareStatement(anyString());
        }

        @Test
        @DisplayName("the canonical UUID, in either case, is the one storable form")
        void storableForms() {
            assertTrue(PostgresIds.isStorableId(UUID));
            assertTrue(PostgresIds.isStorableId(UUID.toUpperCase()));
            assertFalse(PostgresIds.isStorableId(null));
            assertFalse(PostgresIds.isStorableId(OBJECT_ID));
            assertFalse(PostgresIds.isStorableId(UUID.replace("-", "")));
            assertFalse(PostgresIds.isStorableId("{" + UUID + "}"));
            assertFalse(PostgresIds.isStorableId("0000000g-0000-4000-8000-000000000000"));
        }
    }

    @Nested
    @DisplayName("MongoDB")
    @SuppressWarnings("unchecked")
    class Mongo {

        private final MongoDatabase database = mock(MongoDatabase.class);
        private final MongoCollection<Document> collection = mock(MongoCollection.class);
        private final MongoCollection<ConversationMemorySnapshot> snapshots = mock(MongoCollection.class);

        Mongo() {
            when(database.getCollection(anyString())).thenReturn(collection);
            when(database.getCollection("conversationmemories", Document.class)).thenReturn(collection);
            when(database.getCollection("conversationmemories", ConversationMemorySnapshot.class)).thenReturn(snapshots);
        }

        @ParameterizedTest
        @ValueSource(strings = {"xyz", "------------------", "", "0000000000000000000000000000000000000000", UUID})
        @DisplayName("resource storage: not found, no query")
        void resourceStorage(String id) throws Exception {
            var storage = new MongoResourceStorage<>(database, "rulesets", mock(IDocumentBuilder.class), Object.class);
            clearInvocations(collection);

            assertNull(storage.read(id, 1));
            assertNull(storage.readHistory(id, 1));
            assertNull(storage.readHistoryLatest(id));
            assertEquals(-1, storage.getCurrentVersion(id));
            assertTrue(storage.readMany(List.of(idAt(id, 1))).isEmpty());
            storage.remove(id);
            storage.removeAllPermanently(id);

            verifyNoInteractions(collection);
        }

        @ParameterizedTest
        @ValueSource(strings = {"xyz", "------------------", "", "0000000000000000000000000000000000000000", UUID})
        @DisplayName("conversation memory: not found, no query")
        void conversationMemory(String id) throws Exception {
            var store = new ConversationMemoryStore(database);
            clearInvocations(collection, snapshots);

            assertConversationIdIsUnknown(store, id);

            verifyNoInteractions(collection, snapshots);
        }
    }

    @Test
    @DisplayName("every malformed fixture above really is malformed on both backends")
    void fixturesAreMalformedOnBoth() {
        for (String id : MALFORMED_EVERYWHERE) {
            assertFalse(PostgresIds.isStorableId(id), id);
            assertFalse(ObjectId.isValid(id), id);
        }
    }

    private static void assertConversationIdIsUnknown(IConversationMemoryStore store, String id) throws Exception {
        assertNull(store.loadConversationMemorySnapshot(id));
        assertNull(store.getConversationState(id));
        assertNull(store.getRevision(id));
        assertFalse(store.compareAndSetState(id, ConversationState.READY, ConversationState.ENDED));
        store.setConversationState(id, ConversationState.ENDED);
        store.setConversationEndReason(id, "test");
        store.clearHitlBookmark(id);
        store.deleteConversationMemorySnapshot(id);
    }

    private static IResourceStore.IResourceId idAt(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
