/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresDeploymentStorage} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresDeploymentStorage IT")
class PostgresDeploymentStorageTest extends PostgresTestBase {

    private static PostgresDeploymentStorage storage;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        storage = new PostgresDeploymentStorage(dsInstance);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "deployments");
        } catch (SQLException ignored) {
        }
    }

    // ─── CRUD ───────────────────────────────────────────────────

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("setDeploymentInfo + readDeploymentInfo round-trip")
        void setAndRead() throws IResourceStore.ResourceStoreException {
            storage.setDeploymentInfo("production", "agent1", 1, DeploymentStatus.deployed);

            DeploymentInfo info = storage.readDeploymentInfo("production", "agent1", 1);
            assertNotNull(info);
            assertEquals(Environment.production, info.getEnvironment());
            assertEquals("agent1", info.getAgentId());
            assertEquals(1, info.getAgentVersion());
            assertEquals(DeploymentStatus.deployed, info.getDeploymentStatus());
        }

        @Test
        @DisplayName("readDeploymentInfo non-existent — returns null")
        void readNonExistent() throws IResourceStore.ResourceStoreException {
            assertNull(storage.readDeploymentInfo("production", "ghost", 99));
        }

        @Test
        @DisplayName("upsert — updates status on conflict")
        void upsert() throws IResourceStore.ResourceStoreException {
            storage.setDeploymentInfo("production", "agent1", 1, DeploymentStatus.deployed);
            storage.setDeploymentInfo("production", "agent1", 1, DeploymentStatus.undeployed);

            DeploymentInfo info = storage.readDeploymentInfo("production", "agent1", 1);
            assertEquals(DeploymentStatus.undeployed, info.getDeploymentStatus());
        }
    }

    // ─── List Queries ───────────────────────────────────────────

    @Nested
    @DisplayName("List Queries")
    class ListQueries {

        @Test
        @DisplayName("readDeploymentInfos — returns all")
        void readAll() throws IResourceStore.ResourceStoreException {
            storage.setDeploymentInfo("production", "a1", 1, DeploymentStatus.deployed);
            storage.setDeploymentInfo("production", "a2", 1, DeploymentStatus.deployed);
            storage.setDeploymentInfo("test", "a3", 1, DeploymentStatus.deployed);

            List<DeploymentInfo> all = storage.readDeploymentInfos();
            assertEquals(3, all.size());
        }

        @Test
        @DisplayName("readDeploymentInfos with status filter")
        void filterByStatus() throws IResourceStore.ResourceStoreException {
            storage.setDeploymentInfo("production", "a1", 1, DeploymentStatus.deployed);
            storage.setDeploymentInfo("production", "a2", 1, DeploymentStatus.undeployed);
            storage.setDeploymentInfo("production", "a3", 1, DeploymentStatus.deployed);

            List<DeploymentInfo> deployed = storage.readDeploymentInfos("deployed");
            assertEquals(2, deployed.size());

            List<DeploymentInfo> undeployed = storage.readDeploymentInfos("undeployed");
            assertEquals(1, undeployed.size());
        }

        @Test
        @DisplayName("readDeploymentInfos empty — returns empty list")
        void readEmpty() throws IResourceStore.ResourceStoreException {
            assertTrue(storage.readDeploymentInfos().isEmpty());
        }
    }
}
