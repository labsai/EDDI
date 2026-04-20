package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus;
import ai.labs.eddi.configs.deployment.mongo.MongoDeploymentStorage;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoDeploymentStorage} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoDeploymentStorage IT")
class MongoDeploymentStorageIT extends MongoTestBase {

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
}
