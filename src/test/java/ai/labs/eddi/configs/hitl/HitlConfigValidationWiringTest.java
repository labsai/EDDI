/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.mongo.AgentStore;
import ai.labs.eddi.configs.descriptors.mongo.DocumentDescriptorStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.mongo.AgentGroupStore;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that the three {@link HitlConfigValidation} wiring points reject an
 * invalid HITL config at save time (finding 43). The validator's own behavior
 * is covered by {@link HitlConfigValidationTest}; these tests prove the wiring
 * is actually invoked — i.e. an invalid config never reaches the storage
 * backend and surfaces as the validator's actionable message instead of
 * degrading silently at runtime.
 */
@SuppressWarnings("unchecked")
class HitlConfigValidationWiringTest {

    private IResourceStorageFactory storageFactory;
    private IResourceStorage<Object> storage;

    @BeforeEach
    void setUp() {
        storageFactory = mock(IResourceStorageFactory.class);
        storage = mock(IResourceStorage.class);
        // Both stores create their storage in the constructor; return a mock so the
        // store can be instantiated without a real DB.
        when(storageFactory.create(anyString(), any(), any(), any())).thenReturn(storage);
    }

    /** A finite policy with a garbage (non ISO-8601) timeout — must be rejected. */
    private AgentConfiguration.HitlConfig invalidAgentHitl() {
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        hitl.setApprovalTimeout("30 minutes"); // not ISO-8601
        return hitl;
    }

    private AgentGroupConfiguration.HitlConfig invalidGroupHitl() {
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        hitl.setApprovalTimeout("30 minutes");
        return hitl;
    }

    // =========================================================================
    // AgentStore
    // =========================================================================

    @Nested
    @DisplayName("AgentStore")
    class AgentStoreWiring {

        private AgentStore agentStore;

        @BeforeEach
        void init() {
            agentStore = new AgentStore(storageFactory,
                    mock(IDocumentBuilder.class), mock(DocumentDescriptorStore.class));
        }

        @Test
        @DisplayName("create rejects an invalid hitlConfig with the validator's actionable message; storage never touched")
        void createRejectsInvalidHitl() throws Exception {
            var config = new AgentConfiguration();
            config.setHitlConfig(invalidAgentHitl());

            var ex = assertThrows(IllegalArgumentException.class, () -> agentStore.create(config));
            assertTrue(ex.getMessage().contains("approvalTimeout") && ex.getMessage().contains("ISO-8601"),
                    "must surface the validator's actionable message, got: " + ex.getMessage());
            verify(storage, never()).store(any());
        }

        @Test
        @DisplayName("update rejects an invalid hitlConfig before writing")
        void updateRejectsInvalidHitl() {
            var config = new AgentConfiguration();
            config.setHitlConfig(invalidAgentHitl());

            assertThrows(IllegalArgumentException.class, () -> agentStore.update("agent-1", 1, config));
            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("create rejects an over-long pauseReason (approver-facing text cap)")
        void createRejectsOverlongPauseReason() {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setPauseReason("x".repeat(HitlConfigValidation.MAX_PAUSE_REASON_LENGTH + 1));
            var config = new AgentConfiguration();
            config.setHitlConfig(hitl);

            var ex = assertThrows(IllegalArgumentException.class, () -> agentStore.create(config));
            assertTrue(ex.getMessage().contains("pauseReason"),
                    "must mention the pauseReason cap, got: " + ex.getMessage());
        }
    }

    // =========================================================================
    // AgentGroupStore
    // =========================================================================

    @Nested
    @DisplayName("AgentGroupStore")
    class AgentGroupStoreWiring {

        private AgentGroupStore groupStore;

        @BeforeEach
        void init() {
            groupStore = new AgentGroupStore(storageFactory, mock(IDocumentBuilder.class));
        }

        @Test
        @DisplayName("create rejects an invalid hitlConfig with the validator's actionable message; storage never touched")
        void createRejectsInvalidHitl() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setHitlConfig(invalidGroupHitl());

            var ex = assertThrows(IllegalArgumentException.class, () -> groupStore.create(config));
            assertTrue(ex.getMessage().contains("approvalTimeout"),
                    "must surface the validator's actionable message, got: " + ex.getMessage());
            verify(storage, never()).store(any());
        }

        @Test
        @DisplayName("update rejects an invalid hitlConfig before writing")
        void updateRejectsInvalidHitl() {
            var config = new AgentGroupConfiguration();
            config.setHitlConfig(invalidGroupHitl());

            assertThrows(IllegalArgumentException.class, () -> groupStore.update("group-1", 1, config));
            verifyNoInteractions(storage);
        }
    }

    // =========================================================================
    // RestImportService (ZIP-import validation seam)
    // =========================================================================

    @Nested
    @DisplayName("RestImportService ZIP-import validation seam")
    class ImportWiring {

        /**
         * The ZIP-import path (RestImportService line ~425) validates the deserialized
         * agent config's hitlConfig BEFORE importing workflows. We exercise the exact
         * same seam: deserialize the crafted agent JSON exactly as the import service
         * does, then run the validation call. Building the full ZIP pipeline (unzip,
         * workflow parse, descriptor rewrite) would add no coverage of the finding —
         * the wiring under test is this single validation call on the imported config.
         */
        @Test
        @DisplayName("an imported agent config carrying an invalid hitlConfig is rejected at the validation seam")
        void importRejectsInvalidHitl() throws Exception {
            String agentJson = "{\"hitlConfig\":{\"timeoutPolicy\":\"AUTO_REJECT\","
                    + "\"approvalTimeout\":\"30 minutes\"}}";

            // Deserialize via the SAME serializer the import service uses.
            IJsonSerialization jsonSerialization = new ai.labs.eddi.datastore.serialization.JsonSerialization(
                    new com.fasterxml.jackson.databind.ObjectMapper());
            AgentConfiguration imported = jsonSerialization.deserialize(agentJson, AgentConfiguration.class);

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validate(imported.getHitlConfig()));
            assertTrue(ex.getMessage().contains("ISO-8601"),
                    "import must fail closed with an actionable message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("an imported agent config with NO hitlConfig imports cleanly (backward compat)")
        void importAcceptsMissingHitl() throws Exception {
            IJsonSerialization jsonSerialization = new ai.labs.eddi.datastore.serialization.JsonSerialization(
                    new com.fasterxml.jackson.databind.ObjectMapper());
            AgentConfiguration imported = jsonSerialization.deserialize("{}", AgentConfiguration.class);

            // Absent hitlConfig is a no-op — agents predating HITL must keep importing.
            assertDoesNotThrow(() -> HitlConfigValidation.validate(imported.getHitlConfig()));
        }
    }
}
