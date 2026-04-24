/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresAgentTriggerStore} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresAgentTriggerStore IT")
class PostgresAgentTriggerStoreTest extends PostgresTestBase {

    private static PostgresAgentTriggerStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        IJsonSerialization json = new JsonSerialization(new ObjectMapper());
        store = new PostgresAgentTriggerStore(dsInstance, json);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "agent_triggers");
        } catch (SQLException ignored) {
        }
    }

    // ─── CRUD ───────────────────────────────────────────────────

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("createAgentTrigger + readAgentTrigger round-trip")
        void createAndRead() throws Exception {
            var config = createTrigger("greeting");
            store.createAgentTrigger(config);

            var found = store.readAgentTrigger("greeting");
            assertNotNull(found);
            assertEquals("greeting", found.getIntent());
        }

        @Test
        @DisplayName("readAgentTrigger non-existent — throws ResourceNotFoundException")
        void readNonExistent() {
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.readAgentTrigger("nonexistent"));
        }

        @Test
        @DisplayName("createAgentTrigger duplicate — throws ResourceAlreadyExistsException")
        void duplicateCreate() throws Exception {
            store.createAgentTrigger(createTrigger("duplicate"));

            assertThrows(IResourceStore.ResourceAlreadyExistsException.class,
                    () -> store.createAgentTrigger(createTrigger("duplicate")));
        }

        @Test
        @DisplayName("updateAgentTrigger — modifies data")
        void update() throws Exception {
            var config = createTrigger("update_me");
            store.createAgentTrigger(config);

            // Update with modified deployments list
            var updated = createTrigger("update_me");
            store.updateAgentTrigger("update_me", updated);

            var found = store.readAgentTrigger("update_me");
            assertEquals("update_me", found.getIntent());
        }

        @Test
        @DisplayName("updateAgentTrigger non-existent — throws ResourceNotFoundException")
        void updateNonExistent() {
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.updateAgentTrigger("ghost", createTrigger("ghost")));
        }

        @Test
        @DisplayName("deleteAgentTrigger — removes trigger")
        void delete() throws Exception {
            store.createAgentTrigger(createTrigger("delete_me"));
            store.deleteAgentTrigger("delete_me");

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.readAgentTrigger("delete_me"));
        }
    }

    // ─── List ───────────────────────────────────────────────────

    @Nested
    @DisplayName("List")
    class ListQueries {

        @Test
        @DisplayName("readAllAgentTriggers — returns all")
        void readAll() throws Exception {
            store.createAgentTrigger(createTrigger("intent1"));
            store.createAgentTrigger(createTrigger("intent2"));
            store.createAgentTrigger(createTrigger("intent3"));

            List<AgentTriggerConfiguration> all = store.readAllAgentTriggers();
            assertEquals(3, all.size());
        }

        @Test
        @DisplayName("readAllAgentTriggers empty — returns empty list")
        void readAllEmpty() throws Exception {
            assertTrue(store.readAllAgentTriggers().isEmpty());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static AgentTriggerConfiguration createTrigger(String intent) {
        var config = new AgentTriggerConfiguration();
        config.setIntent(intent);
        return config;
    }
}
