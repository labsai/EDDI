/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("AgentTriggerStore")
class AgentTriggerStoreTest {

    @Mock
    private MongoDatabase database;

    @Mock
    private IJsonSerialization jsonSerialization;

    @Mock
    private IDocumentBuilder documentBuilder;

    @Mock
    private MongoCollection<Document> collection;

    @Mock
    private FindIterable<Document> findIterable;

    @Mock
    private MongoCursor<Document> cursor;

    private AgentTriggerStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        openMocks(this);
        doReturn(collection).when(database).getCollection("agenttriggers");
        store = new AgentTriggerStore(database, jsonSerialization, documentBuilder);
    }

    @Nested
    @DisplayName("readAgentTrigger")
    class ReadAgentTrigger {

        @Test
        @DisplayName("should return configuration when document exists")
        void shouldReturnConfig() throws Exception {
            Document doc = new Document("intent", "greet");
            AgentTriggerConfiguration expected = new AgentTriggerConfiguration();
            expected.setIntent("greet");

            doReturn(findIterable).when(collection).find(any(Document.class));
            doReturn(doc).when(findIterable).first();
            doReturn(expected).when(documentBuilder).build(eq(doc), eq(AgentTriggerConfiguration.class));

            AgentTriggerConfiguration result = store.readAgentTrigger("greet");

            assertNotNull(result);
            assertEquals("greet", result.getIntent());
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when document not found")
        void shouldThrowWhenNotFound() {
            doReturn(findIterable).when(collection).find(any(Document.class));
            doReturn(null).when(findIterable).first();

            ResourceNotFoundException ex = assertThrows(
                    ResourceNotFoundException.class,
                    () -> store.readAgentTrigger("nonexistent"));

            assertTrue(ex.getMessage().contains("nonexistent"));
        }

        @Test
        @DisplayName("should wrap IOException as ResourceStoreException")
        void shouldWrapIOException() throws Exception {
            Document doc = new Document("intent", "greet");

            doReturn(findIterable).when(collection).find(any(Document.class));
            doReturn(doc).when(findIterable).first();
            doThrow(new IOException("parse error")).when(documentBuilder).build(eq(doc), eq(AgentTriggerConfiguration.class));

            assertThrows(ResourceStoreException.class, () -> store.readAgentTrigger("greet"));
        }
    }

    @Nested
    @DisplayName("readAllAgentTriggers")
    class ReadAllAgentTriggers {

        @Test
        @DisplayName("should return all trigger configurations")
        void shouldReturnAll() throws Exception {
            Document doc1 = new Document("intent", "greet");
            Document doc2 = new Document("intent", "bye");

            AgentTriggerConfiguration config1 = new AgentTriggerConfiguration();
            config1.setIntent("greet");
            AgentTriggerConfiguration config2 = new AgentTriggerConfiguration();
            config2.setIntent("bye");

            doReturn(findIterable).when(collection).find();
            doReturn(cursor).when(findIterable).iterator();

            doReturn(true, true, false).when(cursor).hasNext();
            doReturn(doc1, doc2).when(cursor).next();

            doReturn(config1).when(documentBuilder).build(eq(doc1), eq(AgentTriggerConfiguration.class));
            doReturn(config2).when(documentBuilder).build(eq(doc2), eq(AgentTriggerConfiguration.class));

            var result = store.readAllAgentTriggers();

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should return empty list when no triggers exist")
        void shouldReturnEmptyList() throws Exception {
            doReturn(findIterable).when(collection).find();
            doReturn(cursor).when(findIterable).iterator();
            doReturn(false).when(cursor).hasNext();

            var result = store.readAllAgentTriggers();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("createAgentTrigger")
    class CreateAgentTrigger {

        @Test
        @DisplayName("should insert document when intent does not exist")
        void shouldInsert() throws Exception {
            AgentTriggerConfiguration config = new AgentTriggerConfiguration();
            config.setIntent("new_intent");

            doReturn(findIterable).when(collection).find(any(Document.class));
            doReturn(null).when(findIterable).first();

            String serialized = "{\"intent\":\"new_intent\"}";
            doReturn(serialized).when(jsonSerialization).serialize(config);
            Document doc = new Document("intent", "new_intent");
            doReturn(doc).when(jsonSerialization).deserialize(eq(serialized), eq(Document.class));

            store.createAgentTrigger(config);

            verify(collection).insertOne(any(Document.class));
        }

        @Test
        @DisplayName("should throw ResourceAlreadyExistsException when intent already exists")
        void shouldThrowWhenAlreadyExists() {
            AgentTriggerConfiguration config = new AgentTriggerConfiguration();
            config.setIntent("existing_intent");

            doReturn(findIterable).when(collection).find(any(Document.class));
            doReturn(new Document("intent", "existing_intent")).when(findIterable).first();

            assertThrows(ResourceAlreadyExistsException.class,
                    () -> store.createAgentTrigger(config));

            verify(collection, never()).insertOne(any(Document.class));
        }
    }

    @Nested
    @DisplayName("updateAgentTrigger")
    class UpdateAgentTrigger {

        @Test
        @DisplayName("should replace document when intent exists")
        void shouldReplace() throws Exception {
            AgentTriggerConfiguration config = new AgentTriggerConfiguration();
            config.setIntent("greet");

            String serialized = "{\"intent\":\"greet\"}";
            doReturn(serialized).when(jsonSerialization).serialize(config);
            Document doc = new Document("intent", "greet");
            doReturn(doc).when(jsonSerialization).deserialize(eq(serialized), eq(Document.class));

            UpdateResult updateResult = mock(UpdateResult.class);
            doReturn(1L).when(updateResult).getMatchedCount();
            doReturn(updateResult).when(collection).replaceOne(any(Document.class), any(Document.class));

            assertDoesNotThrow(() -> store.updateAgentTrigger("greet", config));

            verify(collection).replaceOne(any(Document.class), any(Document.class));
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when no match for update")
        void shouldThrowWhenNotMatched() throws Exception {
            AgentTriggerConfiguration config = new AgentTriggerConfiguration();
            config.setIntent("greet");

            String serialized = "{\"intent\":\"greet\"}";
            doReturn(serialized).when(jsonSerialization).serialize(config);
            Document doc = new Document("intent", "greet");
            doReturn(doc).when(jsonSerialization).deserialize(eq(serialized), eq(Document.class));

            UpdateResult updateResult = mock(UpdateResult.class);
            doReturn(0L).when(updateResult).getMatchedCount();
            doReturn(updateResult).when(collection).replaceOne(any(Document.class), any(Document.class));

            assertThrows(ResourceNotFoundException.class,
                    () -> store.updateAgentTrigger("greet", config));
        }
    }

    @Nested
    @DisplayName("deleteAgentTrigger")
    class DeleteAgentTrigger {

        @Test
        @DisplayName("should delete document when intent exists")
        void shouldDelete() throws Exception {
            DeleteResult deleteResult = mock(DeleteResult.class);
            doReturn(1L).when(deleteResult).getDeletedCount();
            doReturn(deleteResult).when(collection).deleteOne(any(Document.class));

            assertDoesNotThrow(() -> store.deleteAgentTrigger("greet"));

            verify(collection).deleteOne(any(Document.class));
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when delete finds no match")
        void shouldThrowWhenDeleteNotFound() {
            DeleteResult deleteResult = mock(DeleteResult.class);
            doReturn(0L).when(deleteResult).getDeletedCount();
            doReturn(deleteResult).when(collection).deleteOne(any(Document.class));

            ResourceNotFoundException ex = assertThrows(
                    ResourceNotFoundException.class,
                    () -> store.deleteAgentTrigger("nonexistent"));

            assertTrue(ex.getMessage().contains("nonexistent"));
        }
    }
}
