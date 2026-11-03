/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.hitl.oversight.OversightStoreContracts;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.analytics.mongo.MongoApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.mongo.MongoAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.mongo.MongoAuditSampleStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three HITL oversight stores against a real MongoDB (Testcontainers): the
 * shared {@link OversightStoreContracts} plus the indexes each creates.
 */
@DisplayName("HITL oversight stores (MongoDB)")
class MongoOversightStoresIT extends MongoTestBase {

    private static List<String> indexNames(String collection) {
        return getDatabase().getCollection(collection).listIndexes().into(new ArrayList<Document>()).stream()
                .map(index -> index.getString("name")).toList();
    }

    @Nested
    @DisplayName("approval_decisions")
    class Decisions implements OversightStoreContracts.Decisions {

        private IApprovalDecisionStore store;

        @BeforeEach
        void setUp() {
            dropCollections("approval_decisions");
            store = new MongoApprovalDecisionStore(getDatabase());
        }

        @Override
        public IApprovalDecisionStore decisions() {
            return store;
        }

        @Test
        void createsTheQueryIndexes() {
            List<String> names = indexNames("approval_decisions");
            assertTrue(names.containsAll(List.of("idx_decision_agent_category", "idx_decision_group_category", "idx_decision_decided",
                    "idx_decision_user", "idx_decision_decider")), names.toString());
        }
    }

    @Nested
    @DisplayName("audit_samples")
    class Samples implements OversightStoreContracts.Samples {

        private IAuditSampleStore store;

        @BeforeEach
        void setUp() {
            dropCollections("audit_samples");
            store = new MongoAuditSampleStore(getDatabase());
        }

        @Override
        public IAuditSampleStore samples() {
            return store;
        }

        @Test
        void createsTheQueryIndexes() {
            List<String> names = indexNames("audit_samples");
            assertTrue(names.containsAll(List.of("idx_sample_status_due", "idx_sample_agent_category", "idx_sample_agent_status",
                    "idx_sample_user", "idx_sample_reviewer")), names.toString());
        }
    }

    @Nested
    @DisplayName("autonomy_state")
    class States implements OversightStoreContracts.States {

        private IAutonomyStateStore store;

        @BeforeEach
        void setUp() {
            dropCollections("autonomy_state");
            store = new MongoAutonomyStateStore(getDatabase());
        }

        @Override
        public IAutonomyStateStore states() {
            return store;
        }

        @Test
        void createsTheUniqueKey() {
            List<Document> indexes = getDatabase().getCollection("autonomy_state").listIndexes().into(new ArrayList<>());
            Document key = indexes.stream().filter(index -> "idx_autonomy_key".equals(index.getString("name"))).findFirst().orElseThrow();
            assertTrue(key.getBoolean("unique", false));
        }
    }
}
