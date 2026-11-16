/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.groups.budget.ITeamBudgetStore;
import ai.labs.eddi.engine.groups.budget.MongoTeamBudgetStore;
import ai.labs.eddi.engine.groups.budget.TeamBudgetStoreContract;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MongoTeamBudgetStore} against a real MongoDB (Testcontainers): the
 * shared {@link TeamBudgetStoreContract} plus the indexes.
 */
@DisplayName("MongoTeamBudgetStore")
class MongoTeamBudgetStoreIT extends MongoTestBase implements TeamBudgetStoreContract {

    private ITeamBudgetStore store;

    @BeforeEach
    void setUp() {
        store = new MongoTeamBudgetStore(getDatabase());
    }

    @Override
    public ITeamBudgetStore store() {
        return store;
    }

    @Test
    void createsTheLookupIndexAndTheTtlRetention() {
        List<Document> indexes = getDatabase().getCollection("team_budget_months").listIndexes().into(new ArrayList<>());
        List<String> names = indexes.stream().map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_team_budget_group_month", "idx_team_budget_ttl")), names.toString());
        Document ttl = indexes.stream().filter(index -> "idx_team_budget_ttl".equals(index.getString("name"))).findFirst().orElseThrow();
        assertEquals(0L, ((Number) ttl.get("expireAfterSeconds")).longValue());
    }
}
