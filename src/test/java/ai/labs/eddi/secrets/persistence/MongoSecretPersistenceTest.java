/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MongoSecretPersistenceTest {

    private static final String TENANT = "tenant-1";
    private static final ObjectId TEST_OID = new ObjectId("aabbccddeeff112233445566");

    private MongoCollection<Document> secretsCollection;
    private MongoCollection<Document> deksCollection;
    private MongoCollection<Document> metaCollection;
    private MongoSecretPersistence persistence;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        secretsCollection = mock(MongoCollection.class);
        deksCollection = mock(MongoCollection.class);
        metaCollection = mock(MongoCollection.class);

        when(database.getCollection("secretvault_secrets")).thenReturn(secretsCollection);
        when(database.getCollection("secretvault_deks")).thenReturn(deksCollection);
        when(database.getCollection("secretvault_meta")).thenReturn(metaCollection);

        persistence = new MongoSecretPersistence(database);
    }

    // ==================== upsertSecret ====================

    @Test
    @DisplayName("upsertSecret — upserts encrypted secret")
    void upsertSecret() {
        EncryptedSecret secret = createTestSecret();
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(mock(UpdateResult.class));

        assertDoesNotThrow(() -> persistence.upsertSecret(secret));
        verify(secretsCollection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    @DisplayName("upsertSecret — throws PersistenceException on MongoException")
    void upsertSecretError() {
        EncryptedSecret secret = createTestSecret();
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class), any())).thenThrow(new MongoException("fail"));

        assertThrows(PersistenceException.class, () -> persistence.upsertSecret(secret));
    }

    // ==================== findSecret ====================

    @Test
    @DisplayName("findSecret — returns secret when found")
    void findSecretFound() {
        Document doc = createSecretDoc();
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(secretsCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        Optional<EncryptedSecret> result = persistence.findSecret(TENANT, "api-key");
        assertTrue(result.isPresent());
        assertEquals("api-key", result.get().getKeyName());
        assertEquals(TENANT, result.get().getTenantId());
    }

    @Test
    @DisplayName("findSecret — returns empty when not found")
    void findSecretNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(secretsCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        Optional<EncryptedSecret> result = persistence.findSecret(TENANT, "missing");
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("findSecret — throws PersistenceException on MongoException")
    void findSecretError() {
        when(secretsCollection.find(any(Bson.class))).thenThrow(new MongoException("fail"));
        assertThrows(PersistenceException.class, () -> persistence.findSecret(TENANT, "key"));
    }

    // ==================== deleteSecret ====================

    @Test
    @DisplayName("deleteSecret — returns true when deleted")
    void deleteSecretTrue() {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(secretsCollection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        assertTrue(persistence.deleteSecret(TENANT, "api-key"));
    }

    @Test
    @DisplayName("deleteSecret — returns false when not found")
    void deleteSecretFalse() {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(0L);
        when(secretsCollection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        assertFalse(persistence.deleteSecret(TENANT, "missing"));
    }

    // ==================== listSecretsByTenant ====================

    @Test
    @DisplayName("listSecretsByTenant — returns list of secrets")
    void listSecretsByTenant() {
        Document doc = createSecretDoc();
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(secretsCollection.find(any(Bson.class))).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        List<EncryptedSecret> result = persistence.listSecretsByTenant(TENANT);
        assertEquals(1, result.size());
    }

    // ==================== upsertDek ====================

    @Test
    @DisplayName("upsertDek — upserts data encryption key")
    void upsertDek() {
        EncryptedDek dek = new EncryptedDek("id", TENANT, "encDek", "iv", Instant.now());
        when(deksCollection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(mock(UpdateResult.class));

        assertDoesNotThrow(() -> persistence.upsertDek(dek));
        verify(deksCollection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    @DisplayName("upsertDek — throws PersistenceException on error")
    void upsertDekError() {
        EncryptedDek dek = new EncryptedDek("id", TENANT, "encDek", "iv", Instant.now());
        when(deksCollection.updateOne(any(Bson.class), any(Bson.class), any())).thenThrow(new MongoException("fail"));

        assertThrows(PersistenceException.class, () -> persistence.upsertDek(dek));
    }

    // ==================== findDek ====================

    @Test
    @DisplayName("findDek — returns DEK when found")
    void findDekFound() {
        Document doc = new Document("_id", TEST_OID)
                .append("tenantId", TENANT)
                .append("encryptedDek", "enc-data")
                .append("iv", "iv-data")
                .append("createdAt", Instant.now().toString());
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(deksCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        Optional<EncryptedDek> result = persistence.findDek(TENANT);
        assertTrue(result.isPresent());
        assertEquals(TENANT, result.get().getTenantId());
    }

    @Test
    @DisplayName("findDek — returns empty when not found")
    void findDekNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(deksCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertFalse(persistence.findDek(TENANT).isPresent());
    }

    // ==================== deleteDek ====================

    @Test
    @DisplayName("deleteDek — deletes DEK for tenant")
    void deleteDek() {
        when(deksCollection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));
        assertDoesNotThrow(() -> persistence.deleteDek(TENANT));
        verify(deksCollection).deleteOne(any(Bson.class));
    }

    // ==================== listAllDeks ====================

    @Test
    @DisplayName("listAllDeks — returns all DEKs")
    void listAllDeks() {
        Document doc = new Document("_id", TEST_OID)
                .append("tenantId", TENANT)
                .append("encryptedDek", "enc")
                .append("iv", "iv")
                .append("createdAt", Instant.now().toString());
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(deksCollection.find()).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        List<EncryptedDek> result = persistence.listAllDeks();
        assertEquals(1, result.size());
    }

    // ==================== getMetaValue ====================

    @Test
    @DisplayName("getMetaValue — returns value when found")
    void getMetaValueFound() {
        Document doc = new Document("key", "salt").append("value", "abc123");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(metaCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        assertEquals("abc123", persistence.getMetaValue("salt"));
    }

    @Test
    @DisplayName("getMetaValue — returns null when not found")
    void getMetaValueNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(metaCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(persistence.getMetaValue("missing"));
    }

    // ==================== setMetaValue ====================

    @Test
    @DisplayName("setMetaValue — upserts meta key-value")
    void setMetaValue() {
        when(metaCollection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(mock(UpdateResult.class));
        assertDoesNotThrow(() -> persistence.setMetaValue("salt", "abc123"));
        verify(metaCollection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    // ==================== Helpers ====================

    private EncryptedSecret createTestSecret() {
        EncryptedSecret secret = new EncryptedSecret();
        secret.setTenantId(TENANT);
        secret.setKeyName("api-key");
        secret.setEncryptedValue("enc-val");
        secret.setIv("iv-data");
        secret.setDekId("dek-1");
        secret.setChecksum("chk");
        secret.setDescription("Test key");
        secret.setAllowedAgents(List.of("*"));
        secret.setCreatedAt(Instant.now());
        secret.setLastAccessedAt(Instant.now());
        return secret;
    }

    private Document createSecretDoc() {
        return new Document("_id", TEST_OID)
                .append("tenantId", TENANT)
                .append("keyName", "api-key")
                .append("encryptedValue", "enc-val")
                .append("iv", "iv-data")
                .append("dekId", "dek-1")
                .append("checksum", "chk")
                .append("description", "Test key")
                .append("allowedAgents", List.of("*"))
                .append("createdAt", Instant.now().toString())
                .append("lastAccessedAt", Instant.now().toString())
                .append("lastRotatedAt", null);
    }
}
