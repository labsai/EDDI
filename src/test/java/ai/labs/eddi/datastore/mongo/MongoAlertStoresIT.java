/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.alerts.AlertStoreContract;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.mongo.AlertEventStore;
import ai.labs.eddi.engine.alerts.mongo.AlertRuleStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlertRuleStore} and {@link AlertEventStore} against a real MongoDB
 * (Testcontainers): the shared {@link AlertStoreContract} plus the indexes the
 * cluster guarantees rest on.
 */
@DisplayName("Alert stores (MongoDB)")
class MongoAlertStoresIT extends MongoTestBase implements AlertStoreContract {

    private IAlertRuleStore rules;
    private IAlertEventStore events;

    @BeforeEach
    void setUp() {
        dropCollections("alertrules", "alertevents", "alertwindows");
        rules = new AlertRuleStore(getDatabase());
        events = new AlertEventStore(getDatabase());
    }

    @Override
    public IAlertRuleStore ruleStore() {
        return rules;
    }

    @Override
    public IAlertEventStore eventStore() {
        return events;
    }

    @Test
    void createsTheDedupeAndQueryIndexes() {
        List<Document> indexes = getDatabase().getCollection("alertevents").listIndexes().into(new ArrayList<>());
        List<String> names = indexes.stream().map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_alertevent_dedupe", "idx_alertevent_agent_fired", "idx_alertevent_next_attempt",
                "idx_alertevent_rule_state", "idx_alertevent_unacknowledged", "idx_alertevent_rating")), names.toString());
        Document dedupe = indexes.stream().filter(index -> "idx_alertevent_dedupe".equals(index.getString("name"))).findFirst().orElseThrow();
        assertTrue(dedupe.getBoolean("unique", false));
        List<String> windowIndexes = getDatabase().getCollection("alertwindows").listIndexes().into(new ArrayList<>()).stream()
                .map(index -> index.getString("name")).toList();
        assertTrue(windowIndexes.contains("idx_alertwindow_ttl"), windowIndexes.toString());
    }
}
