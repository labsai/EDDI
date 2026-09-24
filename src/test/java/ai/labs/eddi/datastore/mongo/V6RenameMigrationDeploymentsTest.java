/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.deployment.mongo.MongoDeploymentStorage;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rename migration against a real MongoDB, for the case no mock can show:
 * two v5 deployment rows that become the same v6 key.
 *
 * <p>
 * {@code MongoDeploymentStorage} builds its unique index on (environment,
 * agentId, agentVersion) when it is constructed, which at startup is before the
 * migrations run. Two v5 shapes collide under it once renamed: both
 * {@code unrestricted} and {@code restricted} become {@code production}, and
 * v5's own check-then-act upsert wrote same-environment duplicates outright.
 * The second row's write then failed with a duplicate-key error on every boot —
 * the first row was already rewritten, the second never could be — so the
 * migration never completed, the deployment sweep waited on it indefinitely,
 * and no agent was ever deployed again.
 * </p>
 *
 * <p>
 * Ids are fixed and obviously synthetic so that which row is newer does not
 * depend on the order they happen to be read in: in insertion order each
 * collision test puts the newer row on a different side, and the survivor must
 * be the newer one either way.
 * </p>
 */
@DisplayName("V6RenameMigration — deployment rows that collapse onto one v6 key")
class V6RenameMigrationDeploymentsTest extends MongoTestBase {

    private static final ObjectId OLDER = new ObjectId("0000000000000000000000a1");
    private static final ObjectId NEWER = new ObjectId("0000000000000000000000a2");

    private MongoCollection<Document> deployments;
    private IMigrationLogStore migrationLog;

    @BeforeEach
    void clean() {
        dropCollections("deployments", "conversationmemories");
        deployments = getDatabase().getCollection("deployments");
        migrationLog = mock(IMigrationLogStore.class);
        when(migrationLog.readMigrationLog(any())).thenReturn(null);
    }

    /** An EDDI 5 deployment row: v5 field names and a v5 environment value. */
    private static Document v5Row(ObjectId id, String environment, String botId, String status) {
        return new Document("_id", id).append("environment", environment).append("botId", botId).append("botVersion", 1)
                .append("deploymentStatus", status);
    }

    /** Startup order: the store (and its index) first, then the migration. */
    private void boot() {
        new MongoDeploymentStorage(getDatabase(), documentBuilder);
        new V6RenameMigration(getDatabase(), migrationLog, true).runIfNeeded();
    }

    private List<Document> rows() {
        return deployments.find().into(new ArrayList<>());
    }

    /**
     * An agent deployed to both v5 environments. Both map to {@code production};
     * the newer row is read second here, so it arrives to find its key taken and
     * has to take it over.
     */
    @Test
    @DisplayName("unrestricted and restricted rows for one agent collapse to one production row, the newer kept")
    void crossEnvironmentCollision() {
        deployments.insertOne(v5Row(OLDER, "unrestricted", "agent-a", "deployed"));
        deployments.insertOne(v5Row(NEWER, "restricted", "agent-a", "undeployed"));

        boot();

        List<Document> rows = rows();
        assertEquals(1, rows.size(), "exactly one row per v6 key must survive, got: " + rows);
        Document survivor = rows.get(0);
        assertEquals(NEWER, survivor.get("_id"), "the newer row is the operator's last word and must be the one kept");
        assertEquals("production", survivor.get("environment"));
        assertEquals("agent-a", survivor.get("agentId"));
        assertEquals("undeployed", survivor.get("deploymentStatus"));
        assertTrue(rows.stream().noneMatch(row -> row.containsKey("botId")), "no row may be left under its v5 names");
        // and the migration completes, instead of failing the same way on every boot
        verify(migrationLog).createMigrationLog(any(MigrationLog.class));
    }

    /**
     * v5's own duplicate: two rows for the same agent and environment. Here the
     * newer row is read first and takes the key, so the older one arrives second
     * and is the one removed.
     */
    @Test
    @DisplayName("a v5 same-environment duplicate collapses to one row, the newer kept")
    void sameEnvironmentDuplicate() {
        deployments.insertOne(v5Row(NEWER, "unrestricted", "agent-b", "deployed"));
        deployments.insertOne(v5Row(OLDER, "unrestricted", "agent-b", "undeployed"));

        boot();

        List<Document> rows = rows();
        assertEquals(1, rows.size(), "exactly one row per v6 key must survive, got: " + rows);
        assertEquals(NEWER, rows.get(0).get("_id"));
        assertEquals("deployed", rows.get(0).get("deploymentStatus"));
        assertTrue(rows.stream().noneMatch(row -> row.containsKey("botId")), "no row may be left under its v5 names");
        verify(migrationLog).createMigrationLog(any(MigrationLog.class));
    }

    /**
     * "Newest wins" needs an order, and only ObjectIds carry one. Rows with any
     * other kind of id are not guessed between: nothing is deleted, the collision
     * counts as a failure, and the migration stays incomplete so an operator sees
     * it rather than losing a row to a coin toss.
     */
    @Test
    @DisplayName("colliding rows whose ids carry no insert time are left alone, and the migration stays incomplete")
    void unorderableIdsAreNotGuessedBetween() {
        deployments.insertOne(new Document("_id", "row-one").append("environment", "unrestricted").append("botId", "agent-e")
                .append("botVersion", 1).append("deploymentStatus", "deployed"));
        deployments.insertOne(new Document("_id", "row-two").append("environment", "restricted").append("botId", "agent-e")
                .append("botVersion", 1).append("deploymentStatus", "undeployed"));

        boot();

        assertEquals(2, rows().size(), "neither row may be deleted when which one is newer cannot be told");
        verify(migrationLog, never()).createMigrationLog(any(MigrationLog.class));
    }

    /** Rows that do not collide are all migrated and none is removed. */
    @Test
    @DisplayName("rows for different agents are all migrated and none is removed")
    void distinctRowsAreAllKept() {
        deployments.insertOne(v5Row(OLDER, "unrestricted", "agent-c", "deployed"));
        deployments.insertOne(v5Row(NEWER, "restricted", "agent-d", "deployed"));

        boot();

        List<Document> rows = rows();
        assertEquals(2, rows.size(), "nothing collides here, so nothing may be removed: " + rows);
        assertTrue(rows.stream().allMatch(row -> "production".equals(row.get("environment")) && row.containsKey("agentId")));
        verify(migrationLog).createMigrationLog(any(MigrationLog.class));
    }
}
