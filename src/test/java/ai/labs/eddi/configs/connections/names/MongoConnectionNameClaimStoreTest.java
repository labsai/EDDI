/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.names;

import ai.labs.eddi.configs.connections.names.IConnectionNameClaimStore.NameClaim;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MongoConnectionNameClaimStore} against a mocked driver. The assertions
 * are on the filters and updates the store builds, because that is where the
 * guarantee lives: which claim a conditional write can match, and which clock
 * decides staleness.
 */
@SuppressWarnings("unchecked")
class MongoConnectionNameClaimStoreTest {

    private static final String TENANT = "default";
    private static final String NAME = "jira";

    private MongoCollection<Document> claims;
    private MongoConnectionNameClaimStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        claims = mock(MongoCollection.class);
        when(database.getCollection("connection_name_claims")).thenReturn(claims);
        store = new MongoConnectionNameClaimStore(database);
    }

    @Test
    @DisplayName("startup — a unique compound index on (tenantId, name) is what makes a name claimable once")
    void createsTheUniqueIndex() {
        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(claims).createIndex(keys.capture(), options.capture());

        assertEquals(List.of("tenantId", "name"), List.copyOf(render(keys.getValue()).keySet()));
        assertTrue(options.getValue().isUnique(), "without uniqueness two replicas both win the same name");
        assertEquals("idx_connection_name_claim_tenant_name", options.getValue().getName());
    }

    @Test
    @DisplayName("claim — an upsert that can only insert, stamped by the server clock")
    void claimInsertsWithTheServerClock() {
        when(claims.updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class))).thenReturn(mock(UpdateResult.class));

        assertTrue(store.claim(TENANT, NAME, "token-1"));

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<UpdateOptions> options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(claims).updateOne(filter.capture(), update.capture(), options.capture());
        Map<String, BsonValue> clauses = conjunction(filter.getValue());
        assertEquals(TENANT, clauses.get("tenantId").asString().getValue());
        assertEquals(NAME, clauses.get("name").asString().getValue());
        assertEquals("token-1", clauses.get("token").asString().getValue(),
                "the fresh token in the filter is what stops the upsert from ever matching, and so overwriting, another claim");
        assertTrue(options.getValue().isUpsert());
        BsonDocument rendered = render(update.getValue());
        assertTrue(rendered.getDocument("$currentDate").containsKey("claimedAt"), "claimedAt must come from the server, not this JVM");
        assertTrue(rendered.getDocument("$setOnInsert").get("connectionId").isNull());
    }

    @Test
    @DisplayName("claim — a duplicate key means somebody else holds the name")
    void claimMapsADuplicateKeyToFalse() {
        doThrow(new MongoWriteException(new WriteError(11000, "E11000 duplicate key error", new BsonDocument()), new ServerAddress()))
                .when(claims).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));

        assertFalse(store.claim(TENANT, NAME, "token-1"));
    }

    @Test
    @DisplayName("claim — any other write error is a store failure, not a lost claim")
    void claimRethrowsOtherWriteErrors() {
        doThrow(new MongoWriteException(new WriteError(50, "ExceededTimeLimit", new BsonDocument()), new ServerAddress())).when(claims)
                .updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));

        assertThrows(MongoWriteException.class, () -> store.claim(TENANT, NAME, "token-1"));
    }

    @Test
    @DisplayName("find — maps the holder")
    void findMapsTheClaim() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(new Document("tenantId", TENANT).append("name", NAME).append("token", "t").append("connectionId", "c1"));
        when(claims.find(any(Bson.class))).thenReturn(iterable);

        assertEquals(new NameClaim(TENANT, NAME, "t", "c1"), store.find(TENANT, NAME).orElseThrow());
    }

    @Test
    @DisplayName("takeOver — an unrecorded claim is matched only while unrecorded and older than the bound by $$NOW")
    void takeOverOfAnUnrecordedClaimUsesTheServerClock() {
        updateOneMatches(1L);

        assertTrue(store.takeOver(new NameClaim(TENANT, NAME, "old-token", null), "new-token", Duration.ofMinutes(2)));

        Map<String, BsonValue> filter = capturedUpdateFilter();
        assertEquals("old-token", filter.get("token").asString().getValue(), "the compare half of the compare-and-set");
        assertTrue(filter.get("connectionId").isNull(), "a create that recorded itself in the meantime must not be taken over");
        BsonArray lessThan = filter.get("$expr").asDocument().getArray("$lt");
        assertEquals("$claimedAt", lessThan.get(0).asString().getValue());
        BsonArray cutoff = lessThan.get(1).asDocument().getArray("$subtract");
        assertEquals("$$NOW", cutoff.get(0).asString().getValue(), "staleness is judged by the server's clock");
        assertEquals(120_000L, cutoff.get(1).asNumber().longValue());

        BsonDocument update = capturedUpdate();
        assertEquals("new-token", update.getDocument("$set").getString("token").getValue());
        assertTrue(update.getDocument("$set").get("connectionId").isNull());
        assertTrue(update.getDocument("$currentDate").containsKey("claimedAt"));
    }

    @Test
    @DisplayName("takeOver — a recorded claim is matched only while it still names the connection the caller found gone")
    void takeOverOfARecordedClaimComparesTheConnection() {
        updateOneMatches(0L);

        assertFalse(store.takeOver(new NameClaim(TENANT, NAME, "old-token", "c1"), "new-token", Duration.ofMinutes(2)));

        Map<String, BsonValue> filter = capturedUpdateFilter();
        assertEquals("old-token", filter.get("token").asString().getValue());
        assertEquals("c1", filter.get("connectionId").asString().getValue());
        assertFalse(filter.containsKey("$expr"), "age is irrelevant once the recorded connection is known to be gone");
    }

    @Test
    @DisplayName("recordConnection — conditional on the token, so a create whose claim was taken over learns it")
    void recordConnectionIsACompareAndSetOnTheToken() {
        updateOneMatches(1L);

        assertTrue(store.recordConnection(TENANT, NAME, "token-1", "c1"));

        Map<String, BsonValue> filter = capturedUpdateFilter();
        assertEquals("token-1", filter.get("token").asString().getValue());
        assertEquals("c1", capturedUpdate().getDocument("$set").getString("connectionId").getValue());
    }

    @Test
    @DisplayName("release — deletes only the claim holding the token")
    void releaseIsScopedToTheToken() {
        deleteOneRemoves(1L);

        assertTrue(store.release(TENANT, NAME, "token-1"));

        assertEquals("token-1", capturedDeleteFilter().get("token").asString().getValue());
    }

    @Test
    @DisplayName("releaseConnection — deletes only the claim naming that connection")
    void releaseConnectionIsScopedToTheConnection() {
        deleteOneRemoves(0L);

        assertFalse(store.releaseConnection(TENANT, NAME, "c1"));

        Map<String, BsonValue> filter = capturedDeleteFilter();
        assertEquals("c1", filter.get("connectionId").asString().getValue());
        assertEquals(NAME, filter.get("name").asString().getValue());
    }

    private void updateOneMatches(long matched) {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(matched);
        when(claims.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);
    }

    private void deleteOneRemoves(long deleted) {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(deleted);
        when(claims.deleteOne(any(Bson.class))).thenReturn(result);
    }

    private Map<String, BsonValue> capturedUpdateFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(claims).updateOne(filter.capture(), any(Bson.class));
        return conjunction(filter.getValue());
    }

    private BsonDocument capturedUpdate() {
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(claims).updateOne(any(Bson.class), update.capture());
        return render(update.getValue());
    }

    private Map<String, BsonValue> capturedDeleteFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(claims).deleteOne(filter.capture());
        return conjunction(filter.getValue());
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    /** Flattens nested {@code $and}s: which fields are constrained is the point. */
    private static Map<String, BsonValue> conjunction(Bson filter) {
        var flattened = new LinkedHashMap<String, BsonValue>();
        flattenInto(render(filter), flattened);
        return flattened;
    }

    private static void flattenInto(BsonDocument document, Map<String, BsonValue> flattened) {
        for (Map.Entry<String, BsonValue> clause : document.entrySet()) {
            if ("$and".equals(clause.getKey())) {
                for (BsonValue nested : clause.getValue().asArray()) {
                    flattenInto(nested.asDocument(), flattened);
                }
            } else {
                flattened.put(clause.getKey(), clause.getValue());
            }
        }
    }
}
