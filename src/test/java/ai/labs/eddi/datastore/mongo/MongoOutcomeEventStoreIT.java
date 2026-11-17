/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeStoreContract;
import ai.labs.eddi.engine.groups.outcomes.mongo.MongoOutcomeEventStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outcome store against a real MongoDB (Testcontainers). */
@DisplayName("group_outcomes (MongoDB)")
class MongoOutcomeEventStoreIT extends MongoTestBase implements OutcomeStoreContract {

    private IOutcomeEventStore store;

    @BeforeEach
    void setUp() {
        dropCollections("group_outcomes");
        store = new MongoOutcomeEventStore(getDatabase());
    }

    @Override
    public IOutcomeEventStore outcomes() {
        return store;
    }

    @Test
    void createsTheQueryIndexes() {
        List<String> names = getDatabase().getCollection("group_outcomes").listIndexes().into(new ArrayList<Document>()).stream()
                .map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_outcome_group", "idx_outcome_subject", "idx_outcome_user", "idx_outcome_subject_slot")),
                names.toString());
    }
}
