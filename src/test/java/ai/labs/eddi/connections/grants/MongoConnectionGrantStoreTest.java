/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
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
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MongoConnectionGrantStore}, mocking the driver chain
 * MongoDatabase → MongoCollection → FindIterable/MongoCursor.
 * <p>
 * The assertions are mostly on the query and update documents the store builds,
 * because that is where this class's behaviour lives: which rows a conditional
 * write can match, and which columns it is allowed to touch. A test that only
 * checked "updateOne was called" would pass with the version guard, the lease
 * predicate or the claimant scope deleted.
 */
@SuppressWarnings("unchecked")
class MongoConnectionGrantStoreTest {

    private static final String TENANT = "acme";
    private static final String CONNECTION = "jira";
    private static final String PRINCIPAL = "alice";
    private static final ObjectId GRANT_OID = new ObjectId("aabbccddeeff112233445566");

    private static final Instant EXPIRES_AT = Instant.parse("2026-08-22T10:15:30Z");
    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-06-07T08:09:10Z");
    private static final Instant REFRESHED_AT = Instant.parse("2026-08-22T09:15:30Z");
    private static final Instant LEASE_UNTIL = Instant.parse("2026-08-22T09:16:00Z");

    private MongoCollection<Document> grants;
    private MongoConnectionGrantStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        grants = mock(MongoCollection.class);
        when(database.getCollection("connection_grants")).thenReturn(grants);

        store = new MongoConnectionGrantStore(database);
    }

    // ==================== indexes ====================

    @Test
    @DisplayName("startup — the grant key is unique, so one principal cannot end up holding two grants on one connection")
    void createsTheUniqueGrantKeyIndex() {
        // Two rows for the same triple would make find() return whichever the server
        // hands back first, and a disconnect would delete one and leave the other
        // usable.
        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(grants, times(2)).createIndex(keys.capture(), options.capture());

        assertEquals(List.of("tenantId", "connectionName", "principal"), List.copyOf(render(keys.getAllValues().get(0)).keySet()),
                "the prefix order is what lets this index also serve a tenant-only scan");
        assertTrue(options.getAllValues().get(0).isUnique());
        assertEquals("idx_grant_tenant_connection_principal", options.getAllValues().get(0).getName());
    }

    @Test
    @DisplayName("startup — a second, non-unique index serves the lookup of everything one principal connected")
    void createsTheTenantPrincipalIndex() {
        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(grants, times(2)).createIndex(keys.capture(), options.capture());

        assertEquals(List.of("tenantId", "principal"), List.copyOf(render(keys.getAllValues().get(1)).keySet()));
        assertFalse(options.getAllValues().get(1).isUnique(), "one principal legitimately holds a grant per connection, so this key repeats");
        assertEquals("idx_grant_tenant_principal", options.getAllValues().get(1).getName());
    }

    // ==================== find ====================

    @Test
    @DisplayName("find — queries on all three parts of the key")
    void findQueriesTheWholeKey() {
        firstDocumentIs(null);

        store.find(TENANT, CONNECTION, PRINCIPAL);

        Map<String, BsonValue> filter = capturedFindFilter();
        assertEquals(3, filter.size(), "a narrower key would hand back another principal's token ciphertext");
        assertKeyedOnTheGrantTriple(filter);
    }

    @Test
    @DisplayName("find — every stored column is mapped back onto the grant")
    void findMapsTheWholeDocument() {
        firstDocumentIs(fullDocument());

        ConnectionGrant grant = store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();

        assertEquals("aabbccddeeff112233445566", grant.getId());
        assertEquals(TENANT, grant.getTenantId());
        assertEquals(CONNECTION, grant.getConnectionName());
        assertEquals(PRINCIPAL, grant.getPrincipal());
        assertEquals("access-ciphertext", grant.getEncryptedAccessToken());
        assertEquals("access-iv", grant.getAccessTokenIv());
        assertEquals("refresh-ciphertext", grant.getEncryptedRefreshToken());
        assertEquals("refresh-iv", grant.getRefreshTokenIv());
        assertEquals("acme#g2", grant.getDekId(), "without the generation the ciphertext cannot be opened after a key rotation");
        assertEquals(EXPIRES_AT, grant.getExpiresAt());
        assertEquals(List.of("read:jira-work", "write:jira-work"), grant.getScopes());
        assertEquals(ConnectionGrant.Status.REVOKED, grant.getStatus());
        assertEquals(CREATED_AT, grant.getCreatedAt());
        assertEquals(UPDATED_AT, grant.getUpdatedAt());
        assertEquals(REFRESHED_AT, grant.getLastRefreshAt());
        assertEquals(7L, grant.getVersion());
        assertEquals("replica-3", grant.getRefreshInProgress());
        assertEquals(LEASE_UNTIL, grant.getRefreshLeaseExpiresAt());
    }

    @Test
    @DisplayName("find — a document with no _id, no timestamps and no status still maps")
    void findMapsASparseDocument() {
        firstDocumentIs(new Document("tenantId", TENANT).append("connectionName", CONNECTION).append("principal", PRINCIPAL));

        ConnectionGrant grant = store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();

        assertNull(grant.getId());
        assertNull(grant.getExpiresAt());
        assertNull(grant.getScopes());
        assertNull(grant.getCreatedAt());
        assertNull(grant.getUpdatedAt());
        assertNull(grant.getLastRefreshAt());
        assertNull(grant.getRefreshInProgress());
        assertNull(grant.getRefreshLeaseExpiresAt());
        assertEquals(ConnectionGrant.Status.ACTIVE, grant.getStatus(), "a row written before the status column existed is usable, not broken");
        assertEquals(0L, grant.getVersion());
    }

    @Test
    @DisplayName("find — a version stored as a 32-bit integer still reads as a version")
    void findReadsAnIntegerVersion() {
        // Mongo narrows a whole number to Int32 on several write paths. Reading that
        // as "no version" would hand every CAS a guard of 0 and fail every refresh
        // that ever took one.
        firstDocumentIs(new Document("tenantId", TENANT).append("version", 4));

        assertEquals(4L, store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getVersion());
    }

    @Test
    @DisplayName("find — a version that is not a number reads as zero rather than throwing")
    void findFallsBackToZeroForANonNumericVersion() {
        firstDocumentIs(new Document("tenantId", TENANT).append("version", "seven"));

        assertEquals(0L, store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getVersion());
    }

    @Test
    @DisplayName("find — an absent grant is empty, not a blank grant")
    void findReturnsEmptyWhenTheGrantIsNotThere() {
        firstDocumentIs(null);

        assertTrue(store.find(TENANT, CONNECTION, PRINCIPAL).isEmpty(),
                "a blank grant would make 'not connected' look like 'connected but unusable'");
    }

    // ==================== upsert ====================

    @Test
    @DisplayName("upsert — keys on the grant triple and writes every token column")
    void upsertWritesTheTokenColumns() {
        store.upsert(activeGrant());

        Map<String, BsonValue> filter = capturedUpsertFilter();
        assertEquals(3, filter.size());
        assertKeyedOnTheGrantTriple(filter);

        BsonDocument set = capturedUpsertUpdate().getDocument("$set");
        assertEquals("access-ciphertext", set.getString("encryptedAccessToken").getValue());
        assertEquals("access-iv", set.getString("accessTokenIv").getValue());
        assertEquals("refresh-ciphertext", set.getString("encryptedRefreshToken").getValue());
        assertEquals("refresh-iv", set.getString("refreshTokenIv").getValue());
        assertEquals("acme#g2", set.getString("dekId").getValue());
        assertEquals(EXPIRES_AT.toEpochMilli(), set.getDateTime("expiresAt").getValue());
        assertEquals(REFRESHED_AT.toEpochMilli(), set.getDateTime("lastRefreshAt").getValue());
        assertEquals(List.of("read:jira-work"), scopesOf(set));
    }

    @Test
    @DisplayName("upsert — is the one write allowed to create the row")
    void upsertCreatesTheRow() {
        store.upsert(activeGrant());

        assertTrue(capturedUpsertOptions().isUpsert(), "the authorization-code callback has no row to update yet");
    }

    @Test
    @DisplayName("upsert — createdAt is stamped on insert only, so re-consenting does not rewrite when the grant began")
    void upsertStampsCreatedAtOnlyOnInsert() {
        store.upsert(activeGrant());

        BsonDocument update = capturedUpsertUpdate();
        assertTrue(update.getDocument("$setOnInsert").get("createdAt").isDateTime());
        assertFalse(update.getDocument("$set").containsKey("createdAt"), "a $set here would move the grant's birthday on every token write");
        assertTrue(update.getDocument("$set").get("updatedAt").isDateTime());
    }

    @Test
    @DisplayName("upsert — bumps the version, so a refresh still holding the old one loses its guard")
    void upsertBumpsTheVersion() {
        store.upsert(activeGrant());

        BsonDocument update = capturedUpsertUpdate();
        assertEquals(1L, update.getDocument("$inc").getInt64("version").getValue());
        assertFalse(update.getDocument("$set").containsKey("version"), "a $set version would let a stale caller write its own number back");
    }

    @Test
    @DisplayName("upsert — clears any refresh lease, so a fresh grant does not inherit a dead claim")
    void upsertClearsTheLease() {
        store.upsert(activeGrant());

        BsonDocument unset = capturedUpsertUpdate().getDocument("$unset");
        assertEquals(2, unset.size());
        assertTrue(unset.containsKey("refreshInProgress"));
        assertTrue(unset.containsKey("refreshLeaseExpiresAt"), "leaving the expiry behind would strand a claim nothing can free");
    }

    @Test
    @DisplayName("upsert — a grant with no status is stored ACTIVE")
    void upsertDefaultsAMissingStatus() {
        ConnectionGrant grant = activeGrant();
        grant.setStatus(null);

        store.upsert(grant);

        assertEquals("ACTIVE", capturedUpsertUpdate().getDocument("$set").getString("status").getValue());
    }

    @Test
    @DisplayName("upsert — an explicit status is stored as given")
    void upsertKeepsAnExplicitStatus() {
        ConnectionGrant grant = activeGrant();
        grant.setStatus(ConnectionGrant.Status.REFRESH_FAILED);

        store.upsert(grant);

        assertEquals("REFRESH_FAILED", capturedUpsertUpdate().getDocument("$set").getString("status").getValue(),
                "the column holds the enum name; an ordinal would break the moment a constant is inserted");
    }

    @Test
    @DisplayName("upsert — absent timestamps are written as null rather than left behind")
    void upsertWritesNullTimestamps() {
        ConnectionGrant grant = activeGrant();
        grant.setExpiresAt(null);
        grant.setLastRefreshAt(null);

        store.upsert(grant);

        BsonDocument set = capturedUpsertUpdate().getDocument("$set");
        assertTrue(set.get("expiresAt").isNull(), "keeping a previous expiry would make a token that has none look usable");
        assertTrue(set.get("lastRefreshAt").isNull());
    }

    // ==================== claimRefresh ====================

    @Test
    @DisplayName("claimRefresh — a matched row means this caller now owns the refresh")
    void claimRefreshWins() {
        updateOneMatches(1L);

        assertTrue(store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1", LEASE_UNTIL));
    }

    @Test
    @DisplayName("claimRefresh — a lease somebody else holds is not taken")
    void claimRefreshLoses() {
        updateOneMatches(0L);

        assertFalse(store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1", LEASE_UNTIL));
    }

    @Test
    @DisplayName("claimRefresh — a re-claim that rewrites identical values still counts as won")
    void claimRefreshCountsMatchesNotModifications() {
        // Mongo reports zero MODIFIED when the update would write exactly what is
        // already there, which is what the same claimant re-claiming inside one
        // millisecond does. Reading that as a lost claim leaves the caller waiting on
        // a refresh only it was ever going to perform.
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(result.getModifiedCount()).thenReturn(0L);
        when(grants.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertTrue(store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1", LEASE_UNTIL));
        verify(result, never()).getModifiedCount();
    }

    @Test
    @DisplayName("claimRefresh — matches only a lease that is unset, null or already expired")
    void claimRefreshFiltersOnAFreeLease() {
        updateOneMatches(1L);
        // Same clock source as the store, so the comparison cannot skew by a
        // millisecond the way System.currentTimeMillis() can against Instant.now().
        long before = Instant.now().toEpochMilli();

        store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1", LEASE_UNTIL);

        Map<String, BsonValue> filter = capturedUpdateOneFilter();
        assertKeyedOnTheGrantTriple(filter);

        BsonArray free = filter.get("$or").asArray();
        assertEquals(3, free.size(), "missing, explicitly null and expired are three different shapes of a free lease");
        assertFalse(free.get(0).asDocument().getDocument("refreshInProgress").getBoolean("$exists").getValue());
        assertTrue(free.get(1).asDocument().get("refreshInProgress").isNull());
        assertTrue(free.get(2).asDocument().getDocument("refreshLeaseExpiresAt").getDateTime("$lt").getValue() >= before,
                "the cutoff has to be the moment of the claim, or an expired lease never becomes reclaimable");
    }

    @Test
    @DisplayName("claimRefresh — writes the claimant and the lease expiry it was given")
    void claimRefreshWritesTheClaim() {
        updateOneMatches(1L);

        store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1", LEASE_UNTIL);

        BsonDocument set = capturedUpdateOneUpdate().getDocument("$set");
        assertEquals(2, set.size(), "taking a lease is not a token write; nothing else may move");
        assertEquals("replica-1", set.getString("refreshInProgress").getValue());
        assertEquals(LEASE_UNTIL.toEpochMilli(), set.getDateTime("refreshLeaseExpiresAt").getValue());
    }

    @Test
    @DisplayName("claimRefresh — never creates the grant it failed to find")
    void claimRefreshIsNotAnUpsert() {
        updateOneMatches(0L);

        store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1", LEASE_UNTIL);

        verify(grants, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    // ==================== completeRefresh ====================

    @Test
    @DisplayName("completeRefresh — guards the write on the version the claimant read")
    void completeRefreshGuardsOnTheVersion() {
        updateOneMatches(1L);

        assertTrue(store.completeRefresh(activeGrant(), 9L));

        Map<String, BsonValue> filter = capturedUpdateOneFilter();
        assertEquals(4, filter.size());
        assertKeyedOnTheGrantTriple(filter);
        assertEquals(9L, filter.get("version").asInt64().getValue());
    }

    @Test
    @DisplayName("completeRefresh — a version somebody else already spent means the write is refused")
    void completeRefreshLosesTheCas() {
        updateOneMatches(0L);

        assertFalse(store.completeRefresh(activeGrant(), 9L),
                "forcing it through would store a refresh token the provider has already rotated away");
    }

    @Test
    @DisplayName("completeRefresh — a matched row counts as written, even when the bytes are identical")
    void completeRefreshCountsMatchesNotModifications() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(result.getModifiedCount()).thenReturn(0L);
        when(grants.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertTrue(store.completeRefresh(activeGrant(), 9L));
        verify(result, never()).getModifiedCount();
    }

    @Test
    @DisplayName("completeRefresh — clears the lease and bumps the version in the same write")
    void completeRefreshClearsTheLeaseAndBumpsTheVersion() {
        updateOneMatches(1L);

        store.completeRefresh(activeGrant(), 9L);

        BsonDocument update = capturedUpdateOneUpdate();
        assertEquals(2, update.getDocument("$unset").size());
        assertTrue(update.getDocument("$unset").containsKey("refreshInProgress"), "a lease left behind blocks the next refresh until it expires");
        assertTrue(update.getDocument("$unset").containsKey("refreshLeaseExpiresAt"));
        assertEquals(1L, update.getDocument("$inc").getInt64("version").getValue());
        assertFalse(update.containsKey("$setOnInsert"), "this write has no insert branch — a grant that vanished mid-refresh must stay gone");
    }

    @Test
    @DisplayName("completeRefresh — stamps lastRefreshAt from the clock, not from the grant it was handed")
    void completeRefreshStampsItsOwnRefreshTime() {
        updateOneMatches(1L);
        ConnectionGrant grant = activeGrant();
        grant.setLastRefreshAt(null);
        long before = Instant.now().toEpochMilli();

        store.completeRefresh(grant, 9L);

        assertTrue(capturedUpdateOneUpdate().getDocument("$set").getDateTime("lastRefreshAt").getValue() >= before,
                "a caller that left the field unset must not blank out when the token was last renewed");
    }

    @Test
    @DisplayName("completeRefresh — a provider that returned no expiry writes null")
    void completeRefreshWritesANullExpiry() {
        updateOneMatches(1L);
        ConnectionGrant grant = activeGrant();
        grant.setExpiresAt(null);

        store.completeRefresh(grant, 9L);

        assertTrue(capturedUpdateOneUpdate().getDocument("$set").get("expiresAt").isNull());
    }

    @Test
    @DisplayName("completeRefresh — a grant with no status is stored ACTIVE rather than throwing")
    void completeRefreshDefaultsAMissingStatus() {
        // upsert defaulted a null status and this path dereferenced it, so one
        // grant was storable through one write path and fatal through the other
        // — and this is the path that runs after a successful token refresh,
        // where throwing discards the token the provider just issued.
        updateOneMatches(1L);
        ConnectionGrant grant = activeGrant();
        grant.setStatus(null);

        assertTrue(store.completeRefresh(grant, 9L));

        assertEquals("ACTIVE", capturedUpdateOneUpdate().getDocument("$set").getString("status").getValue());
    }

    // ==================== releaseRefresh ====================

    @Test
    @DisplayName("releaseRefresh — only the claimant named in the row can release it")
    void releaseRefreshIsScopedToTheClaimant() {
        store.releaseRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1");

        Map<String, BsonValue> filter = capturedUpdateOneFilter();
        assertEquals(4, filter.size());
        assertKeyedOnTheGrantTriple(filter);
        assertEquals("replica-1", filter.get("refreshInProgress").asString().getValue(),
                "an unconditional release would let a claimant whose lease already expired clear the live claim that replaced it");
    }

    @Test
    @DisplayName("releaseRefresh — clears the two lease columns and touches nothing else")
    void releaseRefreshOnlyClearsTheLease() {
        store.releaseRefresh(TENANT, CONNECTION, PRINCIPAL, "replica-1");

        BsonDocument update = capturedUpdateOneUpdate();
        assertEquals(Set.of("$unset"), update.keySet(),
                "giving up without a new token is not a write; bumping the version here would fail an unrelated CAS");
        assertEquals(2, update.getDocument("$unset").size());
        assertTrue(update.getDocument("$unset").containsKey("refreshInProgress"));
        assertTrue(update.getDocument("$unset").containsKey("refreshLeaseExpiresAt"));
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete — reports true when a grant went away, keyed on the full triple")
    void deleteRemovesTheGrant() {
        deleteOneRemoves(1L);

        assertTrue(store.delete(TENANT, CONNECTION, PRINCIPAL));

        Map<String, BsonValue> filter = capturedDeleteOneFilter();
        assertEquals(3, filter.size(), "anything broader would disconnect connections the user never asked about");
        assertKeyedOnTheGrantTriple(filter);
    }

    @Test
    @DisplayName("delete — reports false when there was nothing to disconnect")
    void deleteFindsNothing() {
        deleteOneRemoves(0L);

        assertFalse(store.delete(TENANT, CONNECTION, PRINCIPAL));
    }

    // ==================== deleteByConnection ====================

    @Test
    @DisplayName("deleteByConnection — removes every principal's grant and reports how many went")
    void deleteByConnectionRemovesEveryPrincipal() {
        deleteManyRemoves(3L);

        assertEquals(3, store.deleteByConnection(TENANT, CONNECTION));

        Map<String, BsonValue> filter = capturedDeleteManyFilter();
        assertEquals(2, filter.size(), "the principal is deliberately absent — tokens must not outlive the connection that produced them");
        assertEquals(TENANT, filter.get("tenantId").asString().getValue());
        assertEquals(CONNECTION, filter.get("connectionName").asString().getValue());
    }

    @Test
    @DisplayName("deleteByConnection — a connection nobody ever connected reports zero")
    void deleteByConnectionRemovesNothing() {
        deleteManyRemoves(0L);

        assertEquals(0, store.deleteByConnection(TENANT, CONNECTION));
    }

    // ==================== findByPrincipal ====================

    @Test
    @DisplayName("findByPrincipal — returns every grant the principal holds, across connections")
    void findByPrincipalReturnsEveryGrant() {
        findReturns(List.of(fullDocument(), documentFor("slack")));

        List<ConnectionGrant> found = store.findByPrincipal(TENANT, PRINCIPAL);

        assertEquals(List.of(CONNECTION, "slack"), found.stream().map(ConnectionGrant::getConnectionName).toList());
    }

    @Test
    @DisplayName("findByPrincipal — queries tenant and principal, never a single connection")
    void findByPrincipalQueriesTenantAndPrincipal() {
        findReturns(List.of());

        assertTrue(store.findByPrincipal(TENANT, PRINCIPAL).isEmpty());

        Map<String, BsonValue> filter = capturedFindFilter();
        assertEquals(2, filter.size());
        assertEquals(TENANT, filter.get("tenantId").asString().getValue());
        assertEquals(PRINCIPAL, filter.get("principal").asString().getValue());
        assertFalse(filter.containsKey("connectionName"), "spanning connections is the point — this is what GDPR erasure enumerates");
    }

    // ==================== findByTenant ====================

    @Test
    @DisplayName("findByTenant — returns every grant in the tenant, which is what a DEK rotation has to re-seal")
    void findByTenantReturnsEveryGrant() {
        findReturns(List.of(fullDocument(), documentFor("slack")));

        assertEquals(2, store.findByTenant(TENANT).size());

        assertEquals(Set.of("tenantId"), capturedFindFilter().keySet(),
                "a rotation that misses a row leaves ciphertext nothing can open once the old generation goes");
    }

    @Test
    @DisplayName("findByTenant — a tenant with no grants yields an empty list, not null")
    void findByTenantReturnsEmpty() {
        findReturns(List.of());

        assertTrue(store.findByTenant(TENANT).isEmpty());
    }

    // ==================== updateSealedTokens ====================

    @Test
    @DisplayName("updateSealedTokens — writes the five ciphertext columns and nothing else")
    void updateSealedTokensWritesOnlyCiphertext() {
        updateOneMatches(1L);

        assertTrue(store.updateSealedTokens(activeGrant(), 9L));

        BsonDocument update = capturedUpdateOneUpdate();
        assertEquals(Set.of("$set"), update.keySet(),
                "a re-seal that bumped the version would fail a concurrent refresh that had nothing wrong with it");
        assertEquals(Set.of("encryptedAccessToken", "accessTokenIv", "encryptedRefreshToken", "refreshTokenIv", "dekId"),
                update.getDocument("$set").keySet(), "updatedAt or a lease column here would make a rotation visible to a refresh in flight");
    }

    @Test
    @DisplayName("updateSealedTokens — guards on the version the row was read at")
    void updateSealedTokensGuardsOnTheVersion() {
        updateOneMatches(1L);

        store.updateSealedTokens(activeGrant(), 9L);

        Map<String, BsonValue> filter = capturedUpdateOneFilter();
        assertEquals(4, filter.size());
        assertEquals(9L, filter.get("version").asInt64().getValue());
        assertKeyedOnTheGrantTriple(filter);
    }

    @Test
    @DisplayName("updateSealedTokens — a row a refresh re-sealed first is left alone")
    void updateSealedTokensLosesTheGuard() {
        updateOneMatches(0L);

        assertFalse(store.updateSealedTokens(activeGrant(), 9L), "the refresh already sealed with the current key, so the work is done");
    }

    @Test
    @DisplayName("updateSealedTokens — a matched row counts as written, even when the bytes are identical")
    void updateSealedTokensCountsMatchesNotModifications() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(result.getModifiedCount()).thenReturn(0L);
        when(grants.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertTrue(store.updateSealedTokens(activeGrant(), 9L));
        verify(result, never()).getModifiedCount();
    }

    @Test
    @DisplayName("updateSealedTokens — never creates the grant a disconnect removed mid-sweep")
    void updateSealedTokensIsNotAnUpsert() {
        updateOneMatches(0L);

        store.updateSealedTokens(activeGrant(), 9L);

        verify(grants, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    // ==================== countByStatus ====================

    @Test
    @DisplayName("countByStatus — counts one tenant's grants in one status")
    void countByStatusCounts() {
        when(grants.countDocuments(any(Bson.class))).thenReturn(12L);

        assertEquals(12L, store.countByStatus(TENANT, ConnectionGrant.Status.REFRESH_FAILED));

        Map<String, BsonValue> filter = capturedCountFilter();
        assertEquals(2, filter.size(), "a count without the tenant would gauge the whole deployment onto one tenant's dashboard");
        assertEquals(TENANT, filter.get("tenantId").asString().getValue());
        assertEquals("REFRESH_FAILED", filter.get("status").asString().getValue());
    }

    @Test
    @DisplayName("countByStatus — a status nobody is in counts zero")
    void countByStatusCountsZero() {
        when(grants.countDocuments(any(Bson.class))).thenReturn(0L);

        assertEquals(0L, store.countByStatus(TENANT, ConnectionGrant.Status.REVOKED));
        assertEquals("REVOKED", capturedCountFilter().get("status").asString().getValue());
    }

    // ==================== Helpers ====================

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    /**
     * Flattens the driver's nested {@code $and} arrays into one field → clause map.
     * Whether {@code Filters.and} nests or merges is a driver detail that has
     * already changed once; what these tests are about is which fields the
     * predicate constrains, so the nesting is normalised away rather than asserted
     * on.
     */
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

    /** Every single-grant read or conditional write must be scoped to one grant. */
    private static void assertKeyedOnTheGrantTriple(Map<String, BsonValue> filter) {
        assertEquals(TENANT, filter.get("tenantId").asString().getValue());
        assertEquals(CONNECTION, filter.get("connectionName").asString().getValue());
        assertEquals(PRINCIPAL, filter.get("principal").asString().getValue());
    }

    private static List<String> scopesOf(BsonDocument set) {
        return set.getArray("scopes").stream().map(value -> value.asString().getValue()).toList();
    }

    private ConnectionGrant activeGrant() {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(CONNECTION);
        grant.setPrincipal(PRINCIPAL);
        grant.setEncryptedAccessToken("access-ciphertext");
        grant.setAccessTokenIv("access-iv");
        grant.setEncryptedRefreshToken("refresh-ciphertext");
        grant.setRefreshTokenIv("refresh-iv");
        grant.setDekId("acme#g2");
        grant.setExpiresAt(EXPIRES_AT);
        grant.setScopes(List.of("read:jira-work"));
        grant.setLastRefreshAt(REFRESHED_AT);
        return grant;
    }

    private Document fullDocument() {
        return new Document("_id", GRANT_OID)
                .append("tenantId", TENANT)
                .append("connectionName", CONNECTION)
                .append("principal", PRINCIPAL)
                .append("encryptedAccessToken", "access-ciphertext")
                .append("accessTokenIv", "access-iv")
                .append("encryptedRefreshToken", "refresh-ciphertext")
                .append("refreshTokenIv", "refresh-iv")
                .append("dekId", "acme#g2")
                .append("expiresAt", Date.from(EXPIRES_AT))
                .append("scopes", List.of("read:jira-work", "write:jira-work"))
                .append("status", "REVOKED")
                .append("createdAt", Date.from(CREATED_AT))
                .append("updatedAt", Date.from(UPDATED_AT))
                .append("lastRefreshAt", Date.from(REFRESHED_AT))
                .append("version", 7L)
                .append("refreshInProgress", "replica-3")
                .append("refreshLeaseExpiresAt", Date.from(LEASE_UNTIL));
    }

    private Document documentFor(String connectionName) {
        return new Document("tenantId", TENANT)
                .append("connectionName", connectionName)
                .append("principal", PRINCIPAL)
                .append("status", "ACTIVE");
    }

    private void firstDocumentIs(Document document) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(document);
        when(grants.find(any(Bson.class))).thenReturn(iterable);
    }

    /** A find() that is walked by a cursor, which is what the driver hands back. */
    private void findReturns(List<Document> documents) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        Iterator<Document> remaining = documents.iterator();
        when(cursor.hasNext()).thenAnswer(invocation -> remaining.hasNext());
        when(cursor.next()).thenAnswer(invocation -> remaining.next());
        doReturn(cursor).when(iterable).iterator();
        when(grants.find(any(Bson.class))).thenReturn(iterable);
    }

    private void updateOneMatches(long matchedCount) {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(matchedCount);
        when(grants.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);
    }

    private void deleteOneRemoves(long deletedCount) {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(deletedCount);
        when(grants.deleteOne(any(Bson.class))).thenReturn(result);
    }

    private void deleteManyRemoves(long deletedCount) {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(deletedCount);
        when(grants.deleteMany(any(Bson.class))).thenReturn(result);
    }

    private Map<String, BsonValue> capturedFindFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(grants).find(filter.capture());
        return conjunction(filter.getValue());
    }

    private Map<String, BsonValue> capturedUpdateOneFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(grants).updateOne(filter.capture(), any(Bson.class));
        return conjunction(filter.getValue());
    }

    private BsonDocument capturedUpdateOneUpdate() {
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(grants).updateOne(any(Bson.class), update.capture());
        return render(update.getValue());
    }

    private Map<String, BsonValue> capturedUpsertFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(grants).updateOne(filter.capture(), any(Bson.class), any(UpdateOptions.class));
        return conjunction(filter.getValue());
    }

    private BsonDocument capturedUpsertUpdate() {
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(grants).updateOne(any(Bson.class), update.capture(), any(UpdateOptions.class));
        return render(update.getValue());
    }

    private UpdateOptions capturedUpsertOptions() {
        ArgumentCaptor<UpdateOptions> options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(grants).updateOne(any(Bson.class), any(Bson.class), options.capture());
        return options.getValue();
    }

    private Map<String, BsonValue> capturedDeleteOneFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(grants).deleteOne(filter.capture());
        return conjunction(filter.getValue());
    }

    private Map<String, BsonValue> capturedDeleteManyFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(grants).deleteMany(filter.capture());
        return conjunction(filter.getValue());
    }

    private Map<String, BsonValue> capturedCountFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(grants).countDocuments(filter.capture());
        return conjunction(filter.getValue());
    }
}
