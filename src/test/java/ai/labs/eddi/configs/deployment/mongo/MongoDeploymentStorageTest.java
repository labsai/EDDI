/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    /**
     * These replace a pair that pinned the old check-then-act (findOneAndReplace,
     * then insertOne when it came back null). Two concurrent deploys of the same
     * agent/version both saw null and both inserted, so those assertions described
     * the defect rather than the requirement.
     */
    @Test
    @DisplayName("setDeploymentInfo — one atomic upsert, never a check-then-act")
    void setDeploymentInfoUpserts() {
        ArgumentCaptor<Document> filterCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<ReplaceOptions> optionsCaptor = ArgumentCaptor.forClass(ReplaceOptions.class);

        storage.setDeploymentInfo("production", "agent-1", 1, DeploymentInfo.DeploymentStatus.deployed);

        verify(collection).replaceOne(filterCaptor.capture(), docCaptor.capture(), optionsCaptor.capture());
        verify(collection, never()).findOneAndReplace(any(Document.class), any(Document.class));
        verify(collection, never()).insertOne(any(Document.class));

        assertTrue(optionsCaptor.getValue().isUpsert(), "the replace must upsert, or the first deploy writes nothing");
        assertEquals("production", filterCaptor.getValue().get("environment"));
        assertEquals("agent-1", filterCaptor.getValue().get("agentId"));
        assertEquals(1, filterCaptor.getValue().get("agentVersion"));
        assertEquals("deployed", docCaptor.getValue().get("deploymentStatus"));
    }

    @Test
    @DisplayName("a unique index on (environment, agentId, agentVersion) backs the upsert")
    void uniqueIndexOnDeploymentKey() {
        ArgumentCaptor<Bson> keyCaptor = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection).createIndex(keyCaptor.capture(), optionsCaptor.capture());

        assertEquals(Boolean.TRUE, optionsCaptor.getValue().isUnique());
        String key = keyCaptor.getValue().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toString();
        assertTrue(key.contains("environment") && key.contains("agentId") && key.contains("agentVersion"),
                "unexpected unique index key: " + key);
    }

    /**
     * A unique index cannot be built over a collection that already holds
     * duplicates — which is exactly the state of the deployments this index exists
     * to protect, since those duplicates are what the check-then-act upsert used to
     * write. Letting the E11000 out of the constructor made the bean
     * unconstructable on precisely those installations, and an unconstructable
     * {@code IDeploymentStore} takes {@code RestAgentStore},
     * {@code RestAgentAdministration} and the startup redeploy with it: the fix
     * bricked the deployments that had the bug.
     */
    @Test
    @DisplayName("a duplicate-key failure building the unique index does not break construction")
    void duplicateRowsDoNotBreakConstruction() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> failingCollection = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(failingCollection);
        when(failingCollection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(mock(MongoCommandException.class));

        MongoDeploymentStorage constructed = assertDoesNotThrow(
                () -> new MongoDeploymentStorage(database, documentBuilder),
                "duplicate rows must not make the deployment store unconstructable");

        // and the store stays usable: the atomic upsert is what actually closes the
        // race, the index is only its backstop.
        constructed.setDeploymentInfo("production", "agent-1", 1, DeploymentInfo.DeploymentStatus.deployed);
        verify(failingCollection).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
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
