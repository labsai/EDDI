/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus;
import ai.labs.eddi.configs.deployment.mongo.MongoDeploymentStorage;
import ai.labs.eddi.datastore.IResourceStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoDeploymentStorage} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoDeploymentStorage IT")
class MongoDeploymentStorageTest extends MongoTestBase {

    private static MongoDeploymentStorage storage;

    @BeforeAll
    static void init() {
        storage = new MongoDeploymentStorage(getDatabase(), documentBuilder);
    }

    @BeforeEach
    void clean() {
        dropCollections("deployments");
    }

    @Test
    @DisplayName("setDeploymentInfo + readDeploymentInfo round-trip")
    void setAndRead() throws IResourceStore.ResourceStoreException {
        storage.setDeploymentInfo("production", "agent1", 1, DeploymentStatus.deployed);

        DeploymentInfo info = storage.readDeploymentInfo("production", "agent1", 1);
        assertNotNull(info);
        assertEquals("production", info.getEnvironment().name());
        assertEquals("agent1", info.getAgentId());
        assertEquals(DeploymentStatus.deployed, info.getDeploymentStatus());
    }

    @Test
    @DisplayName("readDeploymentInfo non-existent — returns null")
    void readNonExistent() throws IResourceStore.ResourceStoreException {
        assertNull(storage.readDeploymentInfo("production", "ghost", 99));
    }

    @Test
    @DisplayName("setDeploymentInfo — replaces on conflict")
    void upsert() throws IResourceStore.ResourceStoreException {
        storage.setDeploymentInfo("production", "a1", 1, DeploymentStatus.deployed);
        storage.setDeploymentInfo("production", "a1", 1, DeploymentStatus.undeployed);

        assertEquals(DeploymentStatus.undeployed,
                storage.readDeploymentInfo("production", "a1", 1).getDeploymentStatus());
    }

    @Test
    @DisplayName("readDeploymentInfos — returns all")
    void readAll() throws IResourceStore.ResourceStoreException {
        storage.setDeploymentInfo("production", "a1", 1, DeploymentStatus.deployed);
        storage.setDeploymentInfo("production", "a2", 1, DeploymentStatus.deployed);

        assertEquals(2, storage.readDeploymentInfos().size());
    }

    @Test
    @DisplayName("readDeploymentInfos with status filter")
    void filterByStatus() throws IResourceStore.ResourceStoreException {
        storage.setDeploymentInfo("production", "a1", 1, DeploymentStatus.deployed);
        storage.setDeploymentInfo("production", "a2", 1, DeploymentStatus.undeployed);

        List<DeploymentInfo> deployed = storage.readDeploymentInfos("deployed");
        assertEquals(1, deployed.size());
    }

    /**
     * The defect this reproduces against a real server, because no mock can: on a
     * database that has not been through the 6.x rename migration the deployment
     * rows carry {@code botId}/{@code botVersion}, Mongo indexes the absent
     * {@code agentId} as null, and an unrestricted unique index on (environment,
     * agentId, agentVersion) therefore reads every one of those rows as a duplicate
     * of every other. Building it fails with E11000, the recovery path
     * deduplicates, and the deduplication keeps ONE row for the entire collection.
     * On a real EDDI 5.5.1 staging database that turned 113 deployment rows into 1
     * before the rename migration had even started, and six of seven agents were
     * never redeployed.
     */
    @Test
    @DisplayName("constructing the store on a pre-rename database keeps every deployment row")
    void preRenameRowsSurviveConstruction() {
        MongoCollection<Document> deployments = getDatabase().getCollection("deployments");
        for (int i = 1; i <= 5; i++) {
            // EDDI 5 field names, and the environment value it used.
            deployments.insertOne(new Document("environment", "unrestricted").append("botId", "bot" + i).append("botVersion", 1)
                    .append("deploymentStatus", "deployed"));
        }

        assertDoesNotThrow(() -> new MongoDeploymentStorage(getDatabase(), documentBuilder));

        assertEquals(5, deployments.countDocuments(), "the pre-rename deployment rows must all still be there");
    }

    /**
     * An installation that already ran an earlier 6.x release carries this key
     * pattern without the partial filter, and Mongo refuses to re-shape an existing
     * index rather than doing so. Unhandled, the conflicting index would simply
     * stay, and with it the behaviour {@link #preRenameRowsSurviveConstruction()}
     * describes.
     *
     * <p>
     * This is the test that establishes which error that is, because it asks a real
     * server: the same key under the same auto-generated name with different
     * options comes back as {@code IndexKeySpecsConflict} (86), not
     * {@code IndexOptionsConflict} (85). Handling 85 alone passes every mocked test
     * and fails this one.
     * </p>
     */
    @Test
    @DisplayName("an existing non-partial unique index is rebuilt as the partial one")
    void existingNonPartialIndexIsRebuilt() {
        MongoCollection<Document> deployments = getDatabase().getCollection("deployments");
        String indexName = deployments.createIndex(Indexes.ascending("environment", "agentId", "agentVersion"),
                new IndexOptions().unique(true));
        assertNull(findIndex(deployments, indexName).get("partialFilterExpression"), "precondition: the old index is not partial");

        assertDoesNotThrow(() -> new MongoDeploymentStorage(getDatabase(), documentBuilder));

        Document rebuilt = findIndex(deployments, indexName);
        assertEquals(Boolean.TRUE, rebuilt.get("unique"), "the rebuilt index must still be unique");
        assertNotNull(rebuilt.get("partialFilterExpression"), "the index must have been rebuilt as a partial one");
    }

    /**
     * A half-migrated collection, which is the only state in which both halves of
     * the fix are exercised at once: three rows that predate the rename and two
     * that genuinely duplicate one key. The partial index still cannot be built —
     * those two rows really are duplicates — so the deduplication runs for a
     * legitimate reason, and it must remove exactly one row. Without the
     * {@code $match} that restricts its pipeline to rows carrying the key, it
     * groups the three pre-rename rows together as well and deletes two of them.
     */
    @Test
    @DisplayName("the dedupe removes a real duplicate and leaves pre-rename rows alone")
    void dedupeSparesPreRenameRows() {
        MongoCollection<Document> deployments = getDatabase().getCollection("deployments");
        for (int i = 1; i <= 3; i++) {
            deployments.insertOne(new Document("environment", "unrestricted").append("botId", "bot" + i).append("botVersion", 1)
                    .append("deploymentStatus", "deployed"));
        }
        deployments.insertOne(new Document("environment", "production").append("agentId", "a1").append("agentVersion", 1)
                .append("deploymentStatus", "deployed"));
        deployments.insertOne(new Document("environment", "production").append("agentId", "a1").append("agentVersion", 1)
                .append("deploymentStatus", "undeployed"));

        assertDoesNotThrow(() -> new MongoDeploymentStorage(getDatabase(), documentBuilder));

        assertEquals(3, deployments.countDocuments(new Document("botId", new Document("$exists", true))),
                "every pre-rename row must survive the dedupe");
        assertEquals(1, deployments.countDocuments(new Document("agentId", "a1")), "exactly one row per real key must survive");
    }

    private static Document findIndex(MongoCollection<Document> collection, String indexName) {
        var names = new ArrayList<String>();
        for (Document index : collection.listIndexes()) {
            names.add(index.getString("name"));
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }
        return fail("no index named " + indexName + ", only: " + names);
    }
}
