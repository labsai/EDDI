/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        persistence = new MongoSecretPersistence(mockDatabase());
    }

    /**
     * Fresh collection mocks, so a test may stub the boot migration before it runs.
     */
    private MongoDatabase mockDatabase() {
        MongoDatabase database = mock(MongoDatabase.class);
        secretsCollection = mock(MongoCollection.class);
        deksCollection = mock(MongoCollection.class);
        metaCollection = mock(MongoCollection.class);

        when(database.getCollection("secretvault_secrets")).thenReturn(secretsCollection);
        when(database.getCollection("secretvault_deks")).thenReturn(deksCollection);
        when(database.getCollection("secretvault_meta")).thenReturn(metaCollection);

        return database;
    }

    // ==================== startup migration ====================

    @Test
    @DisplayName("startup — DEK documents are backfilled BEFORE the unique-on-tenant index is dropped")
    void migratesDeksToGenerations() {
        // Order is the whole point. Backfill first, so every pre-generation document
        // has a generation to be indexed on; then the old index goes, because while
        // it stands a tenant cannot hold a second generation and rotation has
        // nowhere to write.
        var order = inOrder(deksCollection);
        order.verify(deksCollection).updateMany(any(Bson.class), any(Bson.class));
        order.verify(deksCollection).dropIndex("idx_dek_tenant");
        order.verify(deksCollection).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("startup — the backfill covers a stored generation BELOW 1, not just an absent one")
    void migrationBackfillsBelowFirstGeneration() {
        // The entity normalizes what it READS, so a row physically holding 0 is
        // handed out as generation 1 — and then looked up as generation 1 by an
        // exact query that cannot match it. Normalizing only in the entity moves
        // that disagreement rather than removing it, so the row itself is fixed.
        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(deksCollection).updateMany(filter.capture(), any(Bson.class));

        BsonDocument rendered = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        BsonArray alternatives = rendered.getArray("$or");
        assertEquals(2, alternatives.size(), "absent and below-1 are different states and both need migrating: " + rendered.toJson());
        assertFalse(alternatives.get(0).asDocument().getDocument("generation").getBoolean("$exists").getValue(),
                "a document written before generations existed: " + rendered.toJson());
        assertEquals(EncryptedDek.FIRST_GENERATION, alternatives.get(1).asDocument().getDocument("generation").getInt32("$lt").getValue(),
                "and a document that stored a generation no key can be found by: " + rendered.toJson());
    }

    @Test
    @DisplayName("startup — a legacy index that is simply not there is nothing to do")
    void migrationToleratesAnAbsentLegacyIndex() {
        MongoDatabase database = mockDatabase();
        doThrow(commandFailure(27, "index not found with name [idx_dek_tenant]")).when(deksCollection).dropIndex(anyString());

        assertDoesNotThrow(() -> new MongoSecretPersistence(database));
        verify(deksCollection).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("startup — any other drop failure fails the boot instead of passing for 'already gone'")
    void migrationPropagatesANonIndexNotFoundFailure() {
        // Not authorized, or a stepped-down primary: the legacy unique-on-tenantId
        // index may well still be standing, and while it does a tenant cannot hold a
        // second generation and rotation has nowhere to write.
        MongoDatabase database = mockDatabase();
        doThrow(commandFailure(13, "not authorized on eddi to execute command")).when(deksCollection).dropIndex(anyString());

        assertThrows(MongoCommandException.class, () -> new MongoSecretPersistence(database));
        verify(deksCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
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
    @DisplayName("findDek — the ACTIVE generation is the highest one, so the query is sorted")
    void findDekFound() {
        // Unsorted, "the tenant's DEK" is whichever row the server happens to hand
        // back first, and a rotation would sooner or later seal new values with a
        // superseded key.
        FindIterable<Document> iterable = dekIterable(dekDoc(3));
        when(deksCollection.find(any(Bson.class))).thenReturn(iterable);

        Optional<EncryptedDek> result = persistence.findDek(TENANT);

        assertTrue(result.isPresent());
        assertEquals(TENANT, result.get().getTenantId());
        assertEquals(3, result.get().getGeneration());
        assertEquals(TENANT + "#g3", result.get().dekId(), "this is the name ciphertext records, so it has to carry the generation");
        verify(iterable).sort(any(Bson.class));
    }

    @Test
    @DisplayName("findDek — a row written before generations existed reads as generation 1")
    void findDekWithoutGenerationField() {
        Document doc = new Document("_id", TEST_OID)
                .append("tenantId", TENANT)
                .append("encryptedDek", "enc-data")
                .append("iv", "iv-data")
                .append("createdAt", Instant.now().toString());
        // Built before the stubbing starts: dekIterable does its own stubbing, and
        // Mockito rejects a nested when() inside an unfinished one.
        FindIterable<Document> iterable = dekIterable(doc);
        when(deksCollection.find(any(Bson.class))).thenReturn(iterable);

        assertEquals(EncryptedDek.FIRST_GENERATION, persistence.findDek(TENANT).orElseThrow().getGeneration(),
                "that rule is what lets every already-stored row keep working with no migration of ciphertext");
    }

    @Test
    @DisplayName("findDek — returns empty when not found")
    void findDekNotFound() {
        FindIterable<Document> iterable = dekIterable(null);
        when(deksCollection.find(any(Bson.class))).thenReturn(iterable);

        assertFalse(persistence.findDek(TENANT).isPresent());
    }

    @Test
    @DisplayName("findDek(generation) — reads the one generation a stored row names")
    void findDekByGeneration() {
        // Deliberately no sort() stub: the key is unique, so this lookup must not
        // need one, and a production sort() here would fail on the null it returns.
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(deksCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(dekDoc(2));

        Optional<EncryptedDek> result = persistence.findDek(TENANT, 2);

        assertTrue(result.isPresent());
        assertEquals(2, result.get().getGeneration());
    }

    // ==================== insertDek ====================

    @Test
    @DisplayName("insertDek — a fresh generation is inserted")
    void insertDekSucceeds() {
        assertTrue(persistence.insertDek(new EncryptedDek("id", TENANT, 2, "encDek", "iv", Instant.now())));
        verify(deksCollection).insertOne(any(Document.class));
    }

    @Test
    @DisplayName("insertDek — a generation somebody else installed first is refused, not overwritten")
    void insertDekLosesToDuplicateKey() {
        // The commit point of a rotation. Two replicas racing must produce one
        // winner and one clean refusal, never two keys claiming one generation.
        doThrow(new MongoWriteException(new WriteError(11000, "E11000 duplicate key error", new BsonDocument()), new ServerAddress()))
                .when(deksCollection).insertOne(any(Document.class));

        assertFalse(persistence.insertDek(new EncryptedDek("id", TENANT, 2, "encDek", "iv", Instant.now())));
    }

    @Test
    @DisplayName("insertDek — any other write error is a failure, not a lost race")
    void insertDekPropagatesOtherWriteErrors() {
        doThrow(new MongoWriteException(new WriteError(50, "ExceededTimeLimit", new BsonDocument()), new ServerAddress()))
                .when(deksCollection).insertOne(any(Document.class));

        assertThrows(PersistenceException.class, () -> persistence.insertDek(new EncryptedDek("id", TENANT, 2, "encDek", "iv", Instant.now())));
    }

    // ==================== listDeks ====================

    @Test
    @DisplayName("listDeks — every generation the tenant holds, oldest first")
    void listDeks() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(deksCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Bson.class))).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(dekDoc(1), dekDoc(2));

        List<EncryptedDek> result = persistence.listDeks(TENANT);

        assertEquals(List.of(1, 2), result.stream().map(EncryptedDek::getGeneration).toList());
        verify(iterable).sort(any(Bson.class));
    }

    // ==================== updateSecretSealing ====================

    @Test
    @DisplayName("updateSecretSealing — a matched row counts as written, even if the bytes are identical")
    void updateSecretSealingWins() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertTrue(persistence.updateSecretSealing(createTestSecret(), "dek-0"));
    }

    @Test
    @DisplayName("updateSecretSealing — a row somebody else re-sealed first is left alone")
    void updateSecretSealingLosesTheGuard() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(0L);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertFalse(persistence.updateSecretSealing(createTestSecret(), "dek-0"));
    }

    // ==================== updateSecretGrant ====================

    @Test
    @DisplayName("updateSecretGrant — writes ONLY allowedAgents and description, never anything the value depends on")
    void updateSecretGrantTouchesNothingElse() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertTrue(persistence.updateSecretGrant(TENANT, "my-key", List.of("agent-a", "agent-b"), "new description"));

        var update = ArgumentCaptor.forClass(Bson.class);
        verify(secretsCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument set = update.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry())
                .getDocument("$set");

        // The whole point of the endpoint this feeds: a grant edit cannot re-encrypt
        // or blank the secret. Asserted as an exact key set rather than as four
        // absences, so a field added to the $set later fails here too.
        assertEquals(Set.of("allowedAgents", "description"), set.keySet());
        assertEquals(new BsonArray(List.of(new BsonString("agent-a"), new BsonString("agent-b"))), set.getArray("allowedAgents"));
        assertEquals(new BsonString("new description"), set.getString("description"));
    }

    @Test
    @DisplayName("updateSecretGrant — never upserts: a grant for a key that does not exist reports false")
    void updateSecretGrantDoesNotCreateRows() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(0L);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertFalse(persistence.updateSecretGrant(TENANT, "missing-key", List.of("agent-a"), null));

        // The two-arg updateOne, i.e. without UpdateOptions.upsert(true). A typo in a
        // key name has to 404, not leave a valueless row behind.
        verify(secretsCollection).updateOne(any(Bson.class), any(Bson.class));
        verify(secretsCollection, never()).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    @DisplayName("updateSecretGrant — filters on exactly (tenantId, keyName)")
    void updateSecretGrantFilter() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        persistence.updateSecretGrant(TENANT, "my-key", List.of("*"), null);

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(secretsCollection).updateOne(filter.capture(), any(Bson.class));
        String rendered = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(rendered.contains(TENANT), () -> "expected the tenant in the filter, got " + rendered);
        assertTrue(rendered.contains("my-key"), () -> "expected the key name in the filter, got " + rendered);
    }

    @Test
    @DisplayName("updateSecretGrant — a Mongo failure surfaces as PersistenceException")
    void updateSecretGrantWrapsFailures() {
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenThrow(new MongoException("fail"));

        assertThrows(PersistenceException.class, () -> persistence.updateSecretGrant(TENANT, "my-key", List.of("*"), null));
    }

    // ==================== touchLastAccessed ====================

    @Test
    @DisplayName("touchLastAccessed — sets lastAccessedAt and nothing else, without upserting")
    void touchLastAccessedIsOneField() {
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        persistence.touchLastAccessed(TENANT, "my-key", Instant.parse("2026-01-01T00:00:00Z"));

        var update = ArgumentCaptor.forClass(Bson.class);
        verify(secretsCollection).updateOne(any(Bson.class), update.capture());
        verify(secretsCollection, never()).updateOne(any(Bson.class), any(Bson.class), any());
        BsonDocument set = update.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry())
                .getDocument("$set");
        // A resolve records its access with this. Any other field in the $set is a
        // stale value written back over a concurrent grant edit or rotation.
        assertEquals(Set.of("lastAccessedAt"), set.keySet());
        assertEquals(new BsonString("2026-01-01T00:00:00Z"), set.getString("lastAccessedAt"));
    }

    @Test
    @DisplayName("touchLastAccessed — a Mongo failure surfaces as PersistenceException")
    void touchLastAccessedWrapsFailures() {
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenThrow(new MongoException("fail"));

        assertThrows(PersistenceException.class, () -> persistence.touchLastAccessed(TENANT, "my-key", Instant.now()));
    }

    // ==================== deleteDek ====================

    @Test
    @DisplayName("deleteDek — deletes EVERY generation for the tenant")
    void deleteDek() {
        // deleteOne would leave the older generations behind, and a tenant reset
        // that keeps keys is not a reset.
        when(deksCollection.deleteMany(any(Bson.class))).thenReturn(mock(DeleteResult.class));
        assertDoesNotThrow(() -> persistence.deleteDek(TENANT));
        verify(deksCollection).deleteMany(any(Bson.class));
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

    // ==================== putMetaValueIfAbsent / deleteMetaValue (H6a)
    // ====================

    @Test
    @DisplayName("putMetaValueIfAbsent — $setOnInsert only, returning the stored document's value")
    void putMetaValueIfAbsentNeverOverwrites() {
        when(metaCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("key", "salt").append("value", "winner"));

        assertEquals("winner", persistence.putMetaValueIfAbsent("salt", "mine"),
                "a replica that lost the race must be handed the winner's value, not keep its own");

        var update = ArgumentCaptor.forClass(Bson.class);
        var options = ArgumentCaptor.forClass(FindOneAndUpdateOptions.class);
        verify(metaCollection).findOneAndUpdate(any(Bson.class), update.capture(), options.capture());
        BsonDocument rendered = update.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        // An unconditional $set is exactly the race that stranded a replica's DEKs.
        assertEquals(Set.of("$setOnInsert"), rendered.keySet(), rendered.toJson());
        assertTrue(options.getValue().isUpsert());
        assertEquals(ReturnDocument.AFTER, options.getValue().getReturnDocument());
        verify(metaCollection, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    @Test
    @DisplayName("putMetaValueIfAbsent — losing a concurrent upsert to the unique index reads back the winner")
    @SuppressWarnings("unchecked")
    void putMetaValueIfAbsentDuplicateKeyReadsBack() {
        when(metaCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(commandFailure(11000, "E11000 duplicate key error"));
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(new Document("key", "salt").append("value", "winner"));
        when(metaCollection.find(any(Bson.class))).thenReturn(iterable);

        assertEquals("winner", persistence.putMetaValueIfAbsent("salt", "mine"));
    }

    @Test
    @DisplayName("putMetaValueIfAbsent — any other failure is a PersistenceException")
    void putMetaValueIfAbsentOtherFailure() {
        when(metaCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoException("down"));

        assertThrows(PersistenceException.class, () -> persistence.putMetaValueIfAbsent("salt", "mine"));
    }

    @Test
    @DisplayName("deleteMetaValue — deletes the one key")
    void deleteMetaValue() {
        persistence.deleteMetaValue("vault-kek-salt-pending");
        verify(metaCollection).deleteOne(any(Bson.class));
    }

    // ==================== upsertSecret "not supplied" (S1) ====================

    @Test
    @DisplayName("upsertSecret — a null grant is only defaulted on insert, and a null description is not written")
    void upsertSecretKeepsAnUnsuppliedGrantAndDescription() {
        EncryptedSecret secret = createTestSecret();
        secret.setAllowedAgents(null);
        secret.setDescription(null);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class))).thenReturn(mock(UpdateResult.class));

        persistence.upsertSecret(secret);

        var update = ArgumentCaptor.forClass(Bson.class);
        verify(secretsCollection).updateOne(any(Bson.class), update.capture(), any(UpdateOptions.class));
        BsonDocument rendered = update.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertFalse(rendered.getDocument("$set").containsKey("allowedAgents"), "a value rotation must not reset the grant: " + rendered.toJson());
        assertFalse(rendered.getDocument("$set").containsKey("description"), rendered.toJson());
        assertEquals(new BsonArray(List.of(new BsonString("*"))), rendered.getDocument("$setOnInsert").getArray("allowedAgents"));
    }

    @Test
    @DisplayName("upsertSecret — a supplied grant and description are written")
    void upsertSecretWritesASuppliedGrant() {
        EncryptedSecret secret = createTestSecret();
        secret.setAllowedAgents(List.of("agent-a"));
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class))).thenReturn(mock(UpdateResult.class));

        persistence.upsertSecret(secret);

        var update = ArgumentCaptor.forClass(Bson.class);
        verify(secretsCollection).updateOne(any(Bson.class), update.capture(), any(UpdateOptions.class));
        BsonDocument set = update.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).getDocument("$set");
        assertEquals(new BsonArray(List.of(new BsonString("agent-a"))), set.getArray("allowedAgents"));
        assertEquals(new BsonString("Test key"), set.getString("description"));
    }

    // ==================== updateDekWrapping (H6c) / updateSecretGrantIfUnchanged
    // (S6) ====================

    @Test
    @DisplayName("updateDekWrapping — guarded on the IV it read, no upsert, and matched-count decides")
    void updateDekWrappingIsGuarded() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(0L);
        when(deksCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertFalse(persistence.updateDekWrapping(new EncryptedDek("id", TENANT, 2, "newEnc", "newIv", Instant.now()), "oldIv"));

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(deksCollection).updateOne(filter.capture(), any(Bson.class));
        String rendered = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(rendered.contains("oldIv"), rendered);
        verify(deksCollection, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    @Test
    @DisplayName("updateSecretGrantIfUnchanged — the precondition is set equality inside the filter")
    void updateSecretGrantIfUnchangedFilter() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertTrue(persistence.updateSecretGrantIfUnchanged(TENANT, "my-key", List.of("agent-a", "agent-b"), List.of("agent-a"), null));

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(secretsCollection).updateOne(filter.capture(), any(Bson.class));
        String rendered = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        // m5: set equality, which tolerates a stored duplicate; $all+$size did not.
        assertTrue(rendered.contains("$setEquals") && rendered.contains("$ifNull"), rendered);
    }

    @Test
    @DisplayName("updateSecretGrantIfUnchanged — an expected wildcard also matches an absent or empty grant")
    void updateSecretGrantIfUnchangedWildcard() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(secretsCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        persistence.updateSecretGrantIfUnchanged(TENANT, "my-key", List.of("*"), List.of("agent-a"), null);

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(secretsCollection).updateOne(filter.capture(), any(Bson.class));
        String rendered = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(rendered.contains("$or") && rendered.contains("$size"), rendered);
    }

    @Test
    @DisplayName("deleteDekIfWrappedWith — guarded on the IV")
    void deleteDekIfWrappedWithIsGuarded() {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(1L);
        when(deksCollection.deleteOne(any(Bson.class))).thenReturn(result);

        assertTrue(persistence.deleteDekIfWrappedWith(TENANT, 2, "theIv"));

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(deksCollection).deleteOne(filter.capture());
        assertTrue(filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson().contains("theIv"));
    }

    @Test
    @DisplayName("deleteMetaValuesWithPrefix — an anchored, quoted prefix regex")
    void deleteMetaValuesWithPrefix() {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(2L);
        when(metaCollection.deleteMany(any(Bson.class))).thenReturn(result);

        assertEquals(2, persistence.deleteMetaValuesWithPrefix("system-value:"));

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(metaCollection).deleteMany(filter.capture());
        String rendered = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(rendered.contains("^\\\\Qsystem-value:\\\\E"), rendered);
    }

    // ==================== Helpers ====================

    /** A server-side command failure carrying a specific error code. */
    private static MongoCommandException commandFailure(int code, String message) {
        BsonDocument response = new BsonDocument("ok", new BsonDouble(0.0))
                .append("errmsg", new BsonString(message))
                .append("code", new BsonInt32(code));
        return new MongoCommandException(response, new ServerAddress());
    }

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

    private Document dekDoc(int generation) {
        return new Document("_id", TEST_OID)
                .append("tenantId", TENANT)
                .append("generation", generation)
                .append("encryptedDek", "enc-data")
                .append("iv", "iv-data")
                .append("createdAt", Instant.now().toString());
    }

    /** A find() whose sort() chains, which is what the driver actually does. */
    private FindIterable<Document> dekIterable(Document first) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.sort(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(first);
        return iterable;
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
