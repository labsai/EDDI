/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.DeleteResult;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The MongoDB half of the OAuth state store.
 * <p>
 * The row this store guards is the callback's only proof of identity, so the
 * assertions here are about the shape of the queries rather than about round
 * trips: which clauses narrow the compare-and-swap, which clock the cutoff
 * comes from, and that a redeemed state can never be redeemed twice. The
 * Postgres store is pinned the same way in
 * {@link PostgresOAuthStateStoreUnitTest} — the two backends must not drift.
 */
@SuppressWarnings("unchecked")
class MongoOAuthStateStoreTest {

    private static final String COLLECTION = "connection_oauth_states";

    private static final String STATE_TOKEN = "s-9f2c4a1b7e";
    private static final String TENANT = "tenant-1";
    private static final String CONNECTION = "google-mail";
    private static final String PRINCIPAL = "user@example.com";
    private static final String VERIFIER = "verifier-abc";
    private static final String REDIRECT_URI = "https://eddi.example.com/connections/callback";
    private static final String RETURN_TO = "/manage/connections";
    private static final String NONCE_HASH = "nonce-sha256";

    private static final Instant CREATED_AT = Instant.parse("2026-08-22T10:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plus(10, ChronoUnit.MINUTES);
    private static final Instant CONSUMED_AT = CREATED_AT.plus(30, ChronoUnit.SECONDS);

    /**
     * A minute of slack: enough that a slow CI box never fails, far too little for
     * a stale or hard-coded instant to pass.
     */
    private static final long CLOCK_TOLERANCE_MILLIS = 60_000L;

    private MongoDatabase database;
    private MongoCollection<Document> states;
    private MongoOAuthStateStore store;

    @BeforeEach
    void setUp() {
        database = mock(MongoDatabase.class);
        states = mock(MongoCollection.class);
        when(database.getCollection(COLLECTION)).thenReturn(states);

        store = new MongoOAuthStateStore(database);
    }

    // ==================== startup ====================

    @Test
    @DisplayName("startup — state is uniquely indexed and expiry carries a TTL index")
    void createsIndexes() {
        verify(database).getCollection(COLLECTION);

        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(states, times(2)).createIndex(keys.capture(), options.capture());

        int unique = indexOfName(options.getAllValues(), "idx_oauth_state");
        assertTrue(unique >= 0, "expected an index named idx_oauth_state, got " + names(options.getAllValues()));
        // Uniqueness is not tidiness here: two rows sharing a state token would each
        // satisfy the claim predicate independently, and single-use would be gone.
        assertTrue(options.getAllValues().get(unique).isUnique(), "the state index must be unique");
        assertEquals(1, render(keys.getAllValues().get(unique)).getInt32("state").getValue(), "the unique index must be keyed on state");

        int ttl = indexOfName(options.getAllValues(), "idx_oauth_state_ttl");
        assertTrue(ttl >= 0, "expected an index named idx_oauth_state_ttl, got " + names(options.getAllValues()));
        assertEquals(1, render(keys.getAllValues().get(ttl)).getInt32("expiresAt").getValue(), "the TTL index must be keyed on expiresAt");
        assertNotNull(options.getAllValues().get(ttl).getExpireAfter(TimeUnit.SECONDS), "an index with no expireAfter is not a TTL index");
        // expireAfter(0) means "delete once expiresAt passes", not "delete now" —
        // the field value is the deadline, and the offset is added to it.
        assertEquals(0L, options.getAllValues().get(ttl).getExpireAfter(TimeUnit.SECONDS).longValue(),
                "the TTL offset is zero because expiresAt already is the deadline");
    }

    // ==================== create ====================

    @Test
    @DisplayName("create — every field of the in-flight flow is written, and consumedAt is not")
    void createWritesEveryField() {
        store.create(newState());

        ArgumentCaptor<Document> written = ArgumentCaptor.forClass(Document.class);
        verify(states).insertOne(written.capture());
        Document row = written.getValue();

        assertEquals(STATE_TOKEN, row.getString("state"));
        assertEquals(TENANT, row.getString("tenantId"));
        assertEquals(CONNECTION, row.getString("connectionName"));
        assertEquals(PRINCIPAL, row.getString("principal"));
        assertEquals(VERIFIER, row.getString("codeVerifier"), "the PKCE verifier is replayed on exchange, so losing it strands the flow");
        assertEquals(REDIRECT_URI, row.getString("redirectUri"));
        assertEquals(RETURN_TO, row.getString("returnTo"));
        assertEquals(NONCE_HASH, row.getString("nonceHash"), "without the browser binding the state is a bearer token the attacker chose");
        assertEquals(Date.from(CREATED_AT), row.getDate("createdAt"));
        assertEquals(Date.from(EXPIRES_AT), row.getDate("expiresAt"));
        // This absence is what claim()'s $exists branch is written against. Pinning it
        // here means the two halves cannot drift apart in separate changes.
        assertFalse(row.containsKey("consumedAt"), "a freshly issued state is unredeemed, so the field is absent rather than null");
    }

    @Test
    @DisplayName("create — a driver failure is not swallowed")
    void createPropagatesMongoException() {
        MongoException cause = new MongoException("replica set unreachable");
        doThrow(cause).when(states).insertOne(any(Document.class));
        OAuthState state = newState();

        // A silently dropped row means the browser is sent to the provider holding a
        // state nothing will ever recognise, which surfaces much later as an
        // unexplained "invalid state" on the callback.
        assertSame(cause, assertThrows(MongoException.class, () -> store.create(state)));
    }

    // ==================== claim ====================

    @Test
    @DisplayName("claim — the compare-and-swap narrows on state, unredeemed and unexpired, and on nothing else")
    void claimBuildsTheCompareAndSwap() {
        doReturn(claimedRow()).when(states).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));

        store.claim(STATE_TOKEN);

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<FindOneAndUpdateOptions> options = ArgumentCaptor.forClass(FindOneAndUpdateOptions.class);
        verify(states).findOneAndUpdate(filter.capture(), update.capture(), options.capture());

        BsonDocument predicate = conjunction(filter.getValue());
        assertEquals(3, predicate.size(), "exactly three clauses guard the swap: " + predicate.toJson());
        assertEquals(STATE_TOKEN, predicate.getString("state").getValue());

        BsonArray unredeemed = predicate.getArray("$or");
        assertEquals(2, unredeemed.size(), "both spellings of 'not yet redeemed' must match: " + unredeemed.toString());
        assertFalse(unredeemed.get(0).asDocument().getDocument("consumedAt").getBoolean("$exists").getValue(),
                "create() omits the field entirely, so absence has to count as unredeemed");
        assertEquals(BsonNull.VALUE, unredeemed.get(1).asDocument().get("consumedAt"),
                "an explicit null has to count too, or a row written by an older release would be unclaimable");

        BsonDocument mutation = render(update.getValue()).getDocument("$set");
        long cutoff = predicate.getDocument("expiresAt").getDateTime("$gt").getValue();
        long consumedAt = mutation.getDateTime("consumedAt").getValue();
        // Both halves come from one Instant.now(). Two readings would let a state
        // be stamped consumed at an instant it was not yet allowed to be claimed at.
        assertEquals(cutoff, consumedAt, "the expiry cutoff and the consumed stamp are one clock reading");
        assertTrue(Math.abs(cutoff - System.currentTimeMillis()) < CLOCK_TOLERANCE_MILLIS,
                "the cutoff is read from this JVM, which is also what wrote expiresAt: " + cutoff);

        assertEquals(1, mutation.size(), "the swap marks the row redeemed and touches nothing else");
        // Without AFTER the driver hands back the pre-update row, whose consumedAt is
        // still absent — the caller could not tell a claim from a read.
        assertSame(ReturnDocument.AFTER, options.getValue().getReturnDocument(), "the claimed row must come back already stamped");
    }

    @Test
    @DisplayName("claim — the winner gets the whole row back, mapped field for field")
    void claimReturnsTheRow() {
        doReturn(claimedRow()).when(states).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));

        OAuthState claimed = store.claim(STATE_TOKEN).orElseThrow();

        assertEquals(STATE_TOKEN, claimed.getState());
        assertEquals(TENANT, claimed.getTenantId());
        assertEquals(CONNECTION, claimed.getConnectionName());
        // Tenant, connection and principal come off the row, never off the callback's
        // query string — that is the entire reason the row exists.
        assertEquals(PRINCIPAL, claimed.getPrincipal());
        assertEquals(VERIFIER, claimed.getCodeVerifier());
        assertEquals(REDIRECT_URI, claimed.getRedirectUri());
        assertEquals(RETURN_TO, claimed.getReturnTo());
        assertEquals(NONCE_HASH, claimed.getNonceHash());
        assertEquals(CREATED_AT, claimed.getCreatedAt());
        assertEquals(EXPIRES_AT, claimed.getExpiresAt());
        assertEquals(CONSUMED_AT, claimed.getConsumedAt());
    }

    @Test
    @DisplayName("claim — absent, already redeemed and expired are one indistinguishable outcome")
    void claimLosesWhenNoDocumentMatches() {
        // All three fold into the same non-match by construction. Distinguishing them
        // would hand an attacker an oracle for guessing live state tokens.
        doReturn(null).when(states).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));

        assertTrue(store.claim(STATE_TOKEN).isEmpty());
        verify(states).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    @DisplayName("claim — a second attempt on the same state loses, because the first consumed it")
    void claimIsSingleUse() {
        doReturn(claimedRow()).doReturn(null).when(states)
                .findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));

        assertTrue(store.claim(STATE_TOKEN).isPresent(), "the first caller wins");
        assertTrue(store.claim(STATE_TOKEN).isEmpty(), "the second caller must not also redeem the authorization code");
        verify(states, times(2)).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    @DisplayName("claim — a null or blank state never reaches the database")
    void claimRejectsEmptyState() {
        assertTrue(store.claim(null).isEmpty());
        assertTrue(store.claim("   ").isEmpty());

        verify(states, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    @DisplayName("claim — a row with no timestamps maps to null instants instead of throwing")
    void claimToleratesMissingTimestamps() {
        // A row written by a release that predated one of these fields must fail the
        // flow on the missing binding, not on a mapper NPE that reads as a 500.
        doReturn(new Document("state", STATE_TOKEN).append("tenantId", TENANT)).when(states)
                .findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));

        OAuthState claimed = store.claim(STATE_TOKEN).orElseThrow();

        assertEquals(STATE_TOKEN, claimed.getState());
        assertNull(claimed.getCreatedAt());
        assertNull(claimed.getExpiresAt());
        assertNull(claimed.getConsumedAt());
        assertNull(claimed.getNonceHash(), "an unbound row reads as unbound, which the caller rejects on its own terms");
    }

    @Test
    @DisplayName("claim — a driver failure surfaces rather than reading as a lost claim")
    void claimPropagatesMongoException() {
        // Returning empty here would make an outage indistinguishable from a replay,
        // so a database blip would be reported to every user as an invalid state.
        MongoException cause = new MongoException("connection reset by peer");
        doThrow(cause).when(states).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));

        assertSame(cause, assertThrows(MongoException.class, () -> store.claim(STATE_TOKEN)));
    }

    // ==================== deleteExpired ====================

    @Test
    @DisplayName("deleteExpired — deletes only rows already past their expiry, and reports how many")
    void deleteExpiredRemovesPastRows() {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(7L);
        when(states.deleteMany(any(Bson.class))).thenReturn(result);

        assertEquals(7, store.deleteExpired());

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(states).deleteMany(filter.capture());
        BsonDocument predicate = render(filter.getValue());

        // One clause, and it is the expiry bound. A sweep that widened by a field or
        // lost its predicate would take live in-flight flows with it.
        assertEquals(1, predicate.size(), "the sweep is bounded by expiry alone: " + predicate.toJson());
        long cutoff = predicate.getDocument("expiresAt").getDateTime("$lt").getValue();
        assertTrue(Math.abs(cutoff - System.currentTimeMillis()) < CLOCK_TOLERANCE_MILLIS,
                "the sweep uses the same JVM clock claim() does, so the two agree on what is live: " + cutoff);
    }

    @Test
    @DisplayName("deleteExpired — a sweep that deletes nothing reports zero")
    void deleteExpiredReportsZero() {
        DeleteResult result = mock(DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(0L);
        when(states.deleteMany(any(Bson.class))).thenReturn(result);

        assertEquals(0, store.deleteExpired());
    }

    @Test
    @DisplayName("deleteExpired — a driver failure surfaces to the scheduled sweep, which is what handles it")
    void deleteExpiredPropagatesMongoException() {
        // The store does not swallow this: OAuthStateMaintenance does, in the one
        // place that knows a failed retention run is harmless and simply retries.
        MongoException cause = new MongoException("not master");
        when(states.deleteMany(any(Bson.class))).thenThrow(cause);

        assertSame(cause, assertThrows(MongoException.class, () -> store.deleteExpired()));
    }

    // ==================== Helpers ====================

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    /**
     * A predicate as a flat field-to-clause map.
     * <p>
     * The driver renders {@code Filters.and(...)} as a nested {@code $and} array
     * rather than one merged document, and nests further as clauses are combined,
     * so the shape depends on how the filter happened to be assembled. Flattening
     * lets the assertions say WHICH fields the predicate constrains, which is the
     * thing that matters and the thing that stays true across driver versions.
     */
    private static BsonDocument conjunction(Bson bson) {
        BsonDocument flattened = new BsonDocument();
        flattenInto(render(bson), flattened);
        return flattened;
    }

    private static void flattenInto(BsonDocument source, BsonDocument target) {
        source.forEach((field, clause) -> {
            if ("$and".equals(field)) {
                clause.asArray().forEach(nested -> flattenInto(nested.asDocument(), target));
            } else {
                target.put(field, clause);
            }
        });
    }

    private static int indexOfName(List<IndexOptions> options, String name) {
        for (int i = 0; i < options.size(); i++) {
            if (name.equals(options.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> names(List<IndexOptions> options) {
        return options.stream().map(IndexOptions::getName).toList();
    }

    private static OAuthState newState() {
        var state = new OAuthState();
        state.setState(STATE_TOKEN);
        state.setTenantId(TENANT);
        state.setConnectionName(CONNECTION);
        state.setPrincipal(PRINCIPAL);
        state.setCodeVerifier(VERIFIER);
        state.setRedirectUri(REDIRECT_URI);
        state.setReturnTo(RETURN_TO);
        state.setNonceHash(NONCE_HASH);
        state.setCreatedAt(CREATED_AT);
        state.setExpiresAt(EXPIRES_AT);
        return state;
    }

    /**
     * What {@code findOneAndUpdate} returns to the winner: the row, already stamped
     * consumed.
     */
    private static Document claimedRow() {
        return new Document("state", STATE_TOKEN)
                .append("tenantId", TENANT)
                .append("connectionName", CONNECTION)
                .append("principal", PRINCIPAL)
                .append("codeVerifier", VERIFIER)
                .append("redirectUri", REDIRECT_URI)
                .append("returnTo", RETURN_TO)
                .append("nonceHash", NONCE_HASH)
                .append("createdAt", Date.from(CREATED_AT))
                .append("expiresAt", Date.from(EXPIRES_AT))
                .append("consumedAt", Date.from(CONSUMED_AT));
    }
}
