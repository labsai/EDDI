/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.alerts.AlertStoreContract;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresAlertRuleStore} and {@link PostgresAlertEventStore} against a
 * real PostgreSQL (Testcontainers): the shared {@link AlertStoreContract} plus
 * the schema.
 */
@DisplayName("Alert stores (PostgreSQL)")
class PostgresAlertStoresIT extends PostgresTestBase implements AlertStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresAlertRuleStore rules;
    private PostgresAlertEventStore events;

    @BeforeEach
    void setUp() throws Exception {
        rules = new PostgresAlertRuleStore(createDataSourceInstance());
        events = new PostgresAlertEventStore(createDataSourceInstance());
        // The first call creates each schema; then start from empty tables.
        rules.count(null, false);
        events.count(AlertEventFilter.all());
        truncateTables(DATA_SOURCE, "alert_rules", "alert_events", "alert_windows");
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
    void createsTheDedupeAndQueryIndexes() throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt
                        .executeQuery("SELECT indexname FROM pg_indexes WHERE tablename IN ('alert_events', 'alert_rules', 'alert_windows')")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("alert_events_dedupe_key_key", "idx_alert_events_agent_fired", "idx_alert_events_next_attempt",
                "idx_alert_events_rule_state", "idx_alert_events_unacknowledged", "idx_alert_events_rating", "idx_alert_rules_agents",
                "idx_alert_windows_expiry")), indexes.toString());
    }
}
