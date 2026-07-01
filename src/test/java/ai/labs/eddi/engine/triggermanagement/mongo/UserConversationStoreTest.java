/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class UserConversationStoreTest {

    private MongoCollection<Document> collection;
    private IDocumentBuilder documentBuilder;
    private IJsonSerialization jsonSerialization;
    private UserConversationStore store;

    @BeforeEach
    void setUp() throws Exception {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        documentBuilder = mock(IDocumentBuilder.class);
        jsonSerialization = mock(IJsonSerialization.class);

        when(database.getCollection("userconversations")).thenReturn(collection);
        store = new UserConversationStore(database, jsonSerialization, documentBuilder);
    }

    // ==================== readUserConversation ====================

    @Test
    @DisplayName("readUserConversation — returns conversation when found")
    void readUserConversationFound() throws Exception {
        Document doc = new Document("intent", "greeting").append("userId", "user-1");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        UserConversation expected = new UserConversation("greeting", "user-1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(documentBuilder.build(doc, UserConversation.class)).thenReturn(expected);

        UserConversation result = store.readUserConversation("greeting", "user-1");
        assertNotNull(result);
        assertEquals("greeting", result.getIntent());
    }

    @Test
    @DisplayName("readUserConversation — returns null when not found")
    void readUserConversationNotFound() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(store.readUserConversation("missing", "user-1"));
    }

    // ==================== createUserConversation ====================

    @Test
    @DisplayName("createUserConversation — creates when no existing")
    void createUserConversationNew() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize("{}", Document.class)).thenReturn(new Document());

        UserConversation uc = new UserConversation("greeting", "user-1",
                Deployment.Environment.production, "agent-1", "conv-1");

        assertDoesNotThrow(() -> store.createUserConversation(uc));
        verify(collection).insertOne(any(Document.class));
    }

    @Test
    @DisplayName("createUserConversation — throws ResourceAlreadyExistsException when existing")
    void createUserConversationDuplicate() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(new Document("intent", "greeting"));

        UserConversation uc = new UserConversation("greeting", "user-1",
                Deployment.Environment.production, "agent-1", "conv-1");

        assertThrows(ResourceAlreadyExistsException.class, () -> store.createUserConversation(uc));
    }

    // ==================== deleteUserConversation ====================

    @Test
    @DisplayName("deleteUserConversation — deletes by intent and userId")
    void deleteUserConversation() {
        when(collection.deleteOne(any(Document.class))).thenReturn(mock(DeleteResult.class));
        store.deleteUserConversation("greeting", "user-1");
        verify(collection).deleteOne(any(Document.class));
    }

    // ==================== deleteAllForUser ====================

    @Test
    @DisplayName("deleteAllForUser — returns deleted count")
    void deleteAllForUser() {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(3L);
        when(collection.deleteMany(any(Document.class))).thenReturn(deleteResult);

        assertEquals(3L, store.deleteAllForUser("user-1"));
    }

    // ==================== getAllForUser ====================

    @Test
    @DisplayName("getAllForUser — returns all conversations for user")
    void getAllForUser() throws Exception {
        Document doc = new Document("intent", "greeting").append("userId", "user-1");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);

        UserConversation uc = new UserConversation("greeting", "user-1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(documentBuilder.build(any(Document.class), eq(UserConversation.class))).thenReturn(uc);

        doAnswer(inv -> {
            Consumer<Document> consumer = inv.getArgument(0);
            consumer.accept(doc);
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        List<UserConversation> result = store.getAllForUser("user-1");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getAllForUser — wraps IOException in ResourceStoreException")
    void getAllForUserError() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);

        when(documentBuilder.build(any(Document.class), eq(UserConversation.class)))
                .thenThrow(new IOException("parse fail"));

        doAnswer(inv -> {
            Consumer<Document> consumer = inv.getArgument(0);
            consumer.accept(new Document());
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.getAllForUser("user-1"));
    }
}
