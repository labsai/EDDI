/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MongoDeploymentStorageTest {

    private MongoCollection<Document> collection;
    private IDocumentBuilder documentBuilder;
    private MongoDeploymentStorage storage;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        documentBuilder = mock(IDocumentBuilder.class);

        when(database.getCollection("deployments")).thenReturn(collection);
        storage = new MongoDeploymentStorage(database, documentBuilder);
    }

    // ==================== setDeploymentInfo ====================

    @Test
    @DisplayName("setDeploymentInfo — replaces when existing")
    void setDeploymentInfoReplace() {
        Document existing = new Document("environment", "production");
        when(collection.findOneAndReplace(any(Document.class), any(Document.class))).thenReturn(existing);

        storage.setDeploymentInfo("production", "agent-1", 1, DeploymentInfo.DeploymentStatus.deployed);
        verify(collection).findOneAndReplace(any(Document.class), any(Document.class));
        verify(collection, never()).insertOne(any(Document.class));
    }

    @Test
    @DisplayName("setDeploymentInfo — inserts when not existing")
    void setDeploymentInfoInsert() {
        when(collection.findOneAndReplace(any(Document.class), any(Document.class))).thenReturn(null);

        storage.setDeploymentInfo("production", "agent-1", 1, DeploymentInfo.DeploymentStatus.deployed);
        verify(collection).insertOne(any(Document.class));
    }

    // ==================== readDeploymentInfo ====================

    @Test
    @DisplayName("readDeploymentInfo — returns info when found")
    void readDeploymentInfoFound() throws Exception {
        Document doc = new Document("environment", "production");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        DeploymentInfo expected = new DeploymentInfo();
        expected.setAgentId("agent-1");
        when(documentBuilder.build(doc, DeploymentInfo.class)).thenReturn(expected);

        DeploymentInfo result = storage.readDeploymentInfo("production", "agent-1", 1);
        assertNotNull(result);
        assertEquals("agent-1", result.getAgentId());
    }

    @Test
    @DisplayName("readDeploymentInfo — returns null when not found")
    void readDeploymentInfoNotFound() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(storage.readDeploymentInfo("production", "agent-1", 1));
    }

    @Test
    @DisplayName("readDeploymentInfo — wraps IOException")
    void readDeploymentInfoError() throws Exception {
        Document doc = new Document("environment", "production");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        when(documentBuilder.build(doc, DeploymentInfo.class)).thenThrow(new IOException("parse fail"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> storage.readDeploymentInfo("production", "agent-1", 1));
    }

    // ==================== readDeploymentInfos ====================

    @Test
    @DisplayName("readDeploymentInfos — returns all infos without filter")
    void readDeploymentInfosAll() throws Exception {
        Document doc = new Document("environment", "production");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find()).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        DeploymentInfo info = new DeploymentInfo();
        when(documentBuilder.build(doc, DeploymentInfo.class)).thenReturn(info);

        List<DeploymentInfo> result = storage.readDeploymentInfos();
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("readDeploymentInfos — filters by status when provided")
    void readDeploymentInfosFiltered() throws Exception {
        Document doc = new Document("deploymentStatus", "deployed");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        DeploymentInfo info = new DeploymentInfo();
        when(documentBuilder.build(doc, DeploymentInfo.class)).thenReturn(info);

        List<DeploymentInfo> result = storage.readDeploymentInfos("deployed");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("readDeploymentInfos — wraps IOException")
    void readDeploymentInfosError() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find()).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new Document());

        when(documentBuilder.build(any(Document.class), eq(DeploymentInfo.class)))
                .thenThrow(new IOException("parse fail"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> storage.readDeploymentInfos());
    }
}
