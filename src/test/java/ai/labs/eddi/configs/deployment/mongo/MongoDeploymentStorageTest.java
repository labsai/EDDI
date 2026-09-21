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
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;

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

        // Partial, so the index does not span rows written before the 6.x rename
        // migration: those carry botId/botVersion, Mongo indexes the absent agentId as
        // null, and an unrestricted unique index reads every one of them as a
        // duplicate of every other — which the dedupe below then acts on.
        Bson partial = optionsCaptor.getValue().getPartialFilterExpression();
        assertNotNull(partial, "the unique deployment-key index must be partial");
        String filter = partial.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toString();
        assertTrue(filter.contains("agentId") && filter.contains("agentVersion") && filter.contains("exists"),
                "unexpected partial filter: " + filter);
    }

    /**
     * An installation that has already run an earlier 6.x release carries this same
     * key pattern WITHOUT the partial filter — and Mongo does not silently re-shape
     * an existing index: {@code createIndex} refuses, with
     * {@code IndexOptionsConflict} (85) when the old index has a different name and
     * {@code IndexKeySpecsConflict} (86) when it has the same one. Left unhandled,
     * every such installation would keep the unrestricted index, keep reading
     * pre-rename rows as duplicates of one another, and say so only in a log line
     * about duplicate rows. So the conflicting index is dropped and rebuilt.
     */
    @Test
    @DisplayName("an index that already exists with different options is dropped and rebuilt as the partial one")
    void rebuildsAnIndexThatConflictsOnOptions() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> stale = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(stale);

        MongoCommandException conflict = mock(MongoCommandException.class);
        when(conflict.getErrorCode()).thenReturn(85);
        when(stale.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(conflict)
                .thenReturn("environment_1_agentId_1_agentVersion_1");
        // 85 is the same key under a different name, so the stale index is found by
        // its key pattern and dropped by its own name.
        stubIndexes(stale, List.of(ID_INDEX, index("legacy_deployment_key", DEPLOYMENT_KEY_PATTERN)));

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        verify(stale).dropIndex("legacy_deployment_key");
        // Rebuilt, and rebuilt as the partial index — not left as it was found.
        ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);
        verify(stale, times(2)).createIndex(any(Bson.class), optionsCaptor.capture());
        IndexOptions rebuilt = optionsCaptor.getAllValues().get(1);
        assertEquals(Boolean.TRUE, rebuilt.isUnique());
        assertNotNull(rebuilt.getPartialFilterExpression(), "the rebuilt index must be the partial one");

        // No dedupe: the conflict says the index exists, not that the rows are broken.
        verify(stale, never()).aggregate(anyList());
        verify(stale, never()).deleteMany(any(Bson.class));
    }

    /**
     * Only the two index-conflict codes are recovered by dropping the index. A
     * duplicate-key failure (E11000) means the ROWS are wrong, and dropping the
     * index would throw away the constraint instead of fixing them — that case
     * belongs to the dedupe path below.
     */
    @Test
    @DisplayName("a failure that is not an index conflict never drops an index")
    void doesNotDropIndexOnAnUnrelatedFailure() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> duplicated = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(duplicated);

        MongoCommandException duplicateKey = mock(MongoCommandException.class);
        when(duplicateKey.getErrorCode()).thenReturn(11000);
        when(duplicated.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(duplicateKey)
                .thenReturn("environment_1_agentId_1_agentVersion_1");
        stubAggregate(duplicated, List.of());

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        verify(duplicated, never()).dropIndex(any(Bson.class));
        // It went down the dedupe-and-retry path instead.
        verify(duplicated).aggregate(anyList());
    }

    /**
     * {@code IndexKeySpecsConflict} (86) is how current servers report the case
     * that matters most: an earlier release's index on the same key under the same
     * auto-generated name, differing only in options. A review of this change
     * suggested handling 85 alone; the real-server test in
     * {@code datastore.mongo.MongoDeploymentStorageTest} showed that would have
     * left the destructive index in place on every current MongoDB.
     */
    @Test
    @DisplayName("an 86 on our own key pattern (same name, different options) is dropped and rebuilt")
    void rebuildsOnAKeySpecsConflictOverOurOwnKey() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> stale = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(stale);

        MongoCommandException keySpecsConflict = mock(MongoCommandException.class);
        when(keySpecsConflict.getErrorCode()).thenReturn(86);
        when(stale.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(keySpecsConflict)
                .thenReturn("environment_1_agentId_1_agentVersion_1");
        stubIndexes(stale, List.of(ID_INDEX, index("environment_1_agentId_1_agentVersion_1", DEPLOYMENT_KEY_PATTERN)));

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        verify(stale).dropIndex("environment_1_agentId_1_agentVersion_1");
        verify(stale, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    /**
     * 86 also means an index of this name on a <em>different</em> key pattern —
     * someone else's index. Nothing sits on our key, so nothing of ours is stale,
     * and the other index must not be dropped; the conflict goes the ordinary
     * failure path and is reported.
     */
    @Test
    @DisplayName("an 86 over some other key pattern drops nothing")
    void doesNotDropSomeoneElsesIndexOnAKeySpecsConflict() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(collection);

        MongoCommandException keySpecsConflict = mock(MongoCommandException.class);
        when(keySpecsConflict.getErrorCode()).thenReturn(86);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenThrow(keySpecsConflict);
        stubIndexes(collection, List.of(ID_INDEX, index("environment_1_agentId_1_agentVersion_1", new Document("tenant", 1))));
        stubAggregate(collection, List.of());

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        verify(collection, never()).dropIndex(anyString());
        verify(collection, never()).dropIndex(any(Bson.class));
    }

    /**
     * MongoDB allows two indexes on one key pattern when their names and options
     * differ, which is the shape an installation lands in if the partial index was
     * ever built beside the old unrestricted one. Dropping whichever the server
     * happened to list first could drop the good one and leave the conflict
     * standing, so the rebuild would fail and the destructive index would survive.
     */
    @Test
    @DisplayName("every index on the deployment key is dropped, not the first one listed")
    void dropsEveryIndexOnTheDeploymentKey() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(collection);

        MongoCommandException keySpecsConflict = mock(MongoCommandException.class);
        when(keySpecsConflict.getErrorCode()).thenReturn(86);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(keySpecsConflict)
                .thenReturn("environment_1_agentId_1_agentVersion_1");
        stubIndexes(collection, List.of(ID_INDEX,
                index("a_custom_name", DEPLOYMENT_KEY_PATTERN),
                index("environment_1_agentId_1_agentVersion_1", DEPLOYMENT_KEY_PATTERN)));

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        verify(collection).dropIndex("a_custom_name");
        verify(collection).dropIndex("environment_1_agentId_1_agentVersion_1");
        verify(collection, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    /**
     * The case that made dropping by key pattern alone unsafe: an index of some
     * other key holds the name this one would be given, while a perfectly good
     * deployment-key index exists under a name of its own. Dropping ours removes a
     * working constraint and still does not get past the name, so nothing is
     * dropped and the conflict is reported.
     */
    @Test
    @DisplayName("nothing is dropped when a different key holds the name ours would be given")
    void dropsNothingWhenTheGeneratedNameBelongsToAnotherKey() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(collection);

        MongoCommandException keySpecsConflict = mock(MongoCommandException.class);
        when(keySpecsConflict.getErrorCode()).thenReturn(86);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenThrow(keySpecsConflict);
        stubIndexes(collection, List.of(ID_INDEX,
                index("environment_1_agentId_1_agentVersion_1", new Document("tenant", 1)),
                index("our_partial_deployment_key", DEPLOYMENT_KEY_PATTERN)));
        stubAggregate(collection, List.of());

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        verify(collection, never()).dropIndex(anyString());
        verify(collection, never()).dropIndex(any(Bson.class));
    }

    private static final Document DEPLOYMENT_KEY_PATTERN = new Document("environment", 1).append("agentId", 1).append("agentVersion", 1);
    private static final Document ID_INDEX = index("_id_", new Document("_id", 1));

    private static Document index(String name, Document key) {
        return new Document("name", name).append("key", key);
    }

    @SuppressWarnings("unchecked")
    private static void stubIndexes(MongoCollection<Document> collection, List<Document> indexes) {
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        when(collection.listIndexes()).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();

        OngoingStubbing<Boolean> hasNext = when(cursor.hasNext());
        for (int i = 0; i < indexes.size(); i++) {
            hasNext = hasNext.thenReturn(true);
        }
        hasNext.thenReturn(false);
        if (!indexes.isEmpty()) {
            OngoingStubbing<Document> next = when(cursor.next());
            for (Document index : indexes) {
                next = next.thenReturn(index);
            }
        }
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
        stubAggregate(failingCollection, List.of());

        MongoDeploymentStorage constructed = assertDoesNotThrow(
                () -> new MongoDeploymentStorage(database, documentBuilder),
                "duplicate rows must not make the deployment store unconstructable");

        // and the store stays usable — but the race is genuinely still open on such an
        // installation, which is why that failure is now reported at ERROR instead of
        // being described as fixed. See dedupesThenRetriesTheUniqueIndex.
        constructed.setDeploymentInfo("production", "agent-1", 1, DeploymentInfo.DeploymentStatus.deployed);
        verify(failingCollection).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
    }

    /**
     * {@code replaceOne(upsert)} is atomic per operation, but an upsert whose
     * filter fields carry no unique constraint can still insert twice under
     * concurrency: two callers that both match nothing both take the insert branch.
     * Only the unique index turns the loser into a duplicate-key error — so giving
     * up on the index leaves the race open on exactly the installations that
     * already demonstrated it. Removing the duplicates and retrying is what closes
     * it.
     */
    @Test
    @DisplayName("duplicate rows are removed and the unique index is retried, not given up on")
    void dedupesThenRetriesTheUniqueIndex() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> duplicated = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(duplicated);
        // Fails while duplicates are present, succeeds once they are gone.
        when(duplicated.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(mock(MongoCommandException.class))
                .thenReturn("environment_1_agentId_1_agentVersion_1");

        Document duplicateGroup = new Document("duplicateIds", List.of("id-old", "id-new")).append("duplicateCount", 2);
        stubAggregate(duplicated, List.of(duplicateGroup));
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(duplicated.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        ArgumentCaptor<Bson> deleteFilter = ArgumentCaptor.forClass(Bson.class);
        verify(duplicated).deleteMany(deleteFilter.capture());
        String filter = deleteFilter.getValue().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toString();
        assertTrue(filter.contains("id-old"), "the older duplicate must be removed, got: " + filter);
        assertFalse(filter.contains("id-new"), "exactly one row per key must survive, got: " + filter);

        // Twice: once before the dedupe (which failed) and once after it.
        verify(duplicated, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    /**
     * The survivor is picked as the LAST element of the {@code $push} array, so the
     * array's order is the whole correctness argument — and {@code $push} only
     * preserves an order the pipeline actually established. Without a leading
     * {@code $sort} the input order is the storage engine's natural order, which is
     * neither insertion order nor stable across nodes: two nodes deduplicating
     * during a rolling restart can each keep a different element and, between them,
     * delete every row for a key, leaving a deployed agent silently un-redeployed.
     *
     * <p>
     * This asserts the pipeline the code actually sends, because the mock in
     * {@link #dedupesThenRetriesTheUniqueIndex} supplies the array order itself and
     * so can never notice that the server was never asked for one.
     * </p>
     */
    @Test
    @DisplayName("the dedupe pipeline sorts by _id before grouping, so every node keeps the same row")
    void dedupePipelineSortsBeforeGrouping() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> duplicated = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(duplicated);
        when(duplicated.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(mock(MongoCommandException.class))
                .thenReturn("environment_1_agentId_1_agentVersion_1");
        stubAggregate(duplicated, List.of());

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        ArgumentCaptor<List<Document>> pipeline = ArgumentCaptor.forClass(List.class);
        verify(duplicated).aggregate(pipeline.capture());
        List<Document> stages = pipeline.getValue();

        int sortStage = indexOfStage(stages, "$sort");
        int groupStage = indexOfStage(stages, "$group");
        assertTrue(sortStage >= 0, "the dedupe pipeline must sort before it groups, got: " + stages);
        assertTrue(groupStage >= 0, "the dedupe pipeline must group, got: " + stages);
        assertTrue(sortStage < groupStage, "the $sort must precede the $group or $push order is undefined, got: " + stages);
        assertEquals(new Document("_id", 1), stages.get(sortStage).get("$sort"),
                "sorting must be ascending by _id — an ObjectId's leading bytes are the insert timestamp, so that is insertion order");
    }

    /**
     * The dedupe is the recovery path, so its own failure must not become the thing
     * it was recovering from. Nothing in this constructor may make the bean
     * unconstructable: an unconstructable {@code IDeploymentStore} takes
     * {@code RestAgentStore}, {@code RestAgentAdministration} and the startup
     * redeploy down with it. The collection is left exactly as it was — no delete
     * is attempted on an aggregation that never answered — and the index is not
     * retried, because nothing changed.
     */
    @Test
    @DisplayName("a dedupe that itself fails leaves the store constructible and deletes nothing")
    void dedupeFailureDoesNotBreakConstructionOrDeleteAnything() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> broken = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(broken);
        when(broken.createIndex(any(Bson.class), any(IndexOptions.class))).thenThrow(mock(MongoCommandException.class));
        when(broken.aggregate(anyList())).thenThrow(mock(MongoCommandException.class));

        MongoDeploymentStorage constructed = assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder),
                "a failing dedupe must not make the deployment store unconstructable");

        verify(broken, never()).deleteMany(any(Bson.class));
        // Once, not twice: the retry is only earned by a dedupe that actually ran.
        verify(broken).createIndex(any(Bson.class), any(IndexOptions.class));

        // The collection stays usable.
        constructed.setDeploymentInfo("production", "agent-1", 1, DeploymentInfo.DeploymentStatus.deployed);
        verify(broken).replaceOne(any(Document.class), any(Document.class), any(ReplaceOptions.class));
    }

    /**
     * A group the {@code $match} let through but that carries fewer than two ids
     * describes no duplicate, so nothing may be deleted for it. The guard is what
     * stops {@code ids.subList(0, ids.size() - 1)} on a one-element (or absent)
     * array from turning a bad aggregation result into data loss.
     */
    @Test
    @DisplayName("a group with fewer than two ids deletes nothing")
    void groupWithoutARealDuplicateDeletesNothing() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> odd = mock(MongoCollection.class);
        when(database.getCollection("deployments")).thenReturn(odd);
        when(odd.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(mock(MongoCommandException.class))
                .thenReturn("environment_1_agentId_1_agentVersion_1");
        stubAggregate(odd, List.of(new Document("duplicateIds", List.of("only-one")).append("duplicateCount", 2),
                new Document("duplicateCount", 2)));

        assertDoesNotThrow(() -> new MongoDeploymentStorage(database, documentBuilder));

        verify(odd, never()).deleteMany(any(Bson.class));
        // The index is still retried — the dedupe ran, it simply found nothing to do.
        verify(odd, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    private static int indexOfStage(List<Document> stages, String stageName) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).containsKey(stageName)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static void stubAggregate(MongoCollection<Document> collection, List<Document> groups) {
        AggregateIterable<Document> iterable = mock(AggregateIterable.class);
        when(collection.aggregate(anyList())).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();

        OngoingStubbing<Boolean> hasNext = when(cursor.hasNext());
        for (int i = 0; i < groups.size(); i++) {
            hasNext = hasNext.thenReturn(true);
        }
        hasNext.thenReturn(false);

        if (!groups.isEmpty()) {
            OngoingStubbing<Document> next = when(cursor.next());
            for (Document group : groups) {
                next = next.thenReturn(group);
            }
        }
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
