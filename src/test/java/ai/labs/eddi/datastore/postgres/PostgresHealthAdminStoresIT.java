/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.health.HealthAdminStoresContract;
import ai.labs.eddi.engine.health.IHealthAlertStateStore;
import ai.labs.eddi.engine.health.IHealthProfileStore;
import ai.labs.eddi.engine.health.IHealthReportLinkStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthAlertStateStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthProfileStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthReportLinkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * {@link HealthAdminStoresContract} against PostgreSQL (Testcontainers).
 */
@DisplayName("Agent Health profile, alert-state and report-link stores (PostgreSQL)")
class PostgresHealthAdminStoresIT extends PostgresTestBase implements HealthAdminStoresContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private IHealthProfileStore profiles;
    private IHealthAlertStateStore alertStates;
    private IHealthReportLinkStore reportLinks;

    @BeforeEach
    void setUp() throws Exception {
        profiles = new PostgresHealthProfileStore(createDataSourceInstance());
        alertStates = new PostgresHealthAlertStateStore(createDataSourceInstance());
        reportLinks = new PostgresHealthReportLinkStore(createDataSourceInstance());
        // The first call of each store creates its schema; then start from empty
        // tables.
        profiles.read("nobody");
        alertStates.deleteEvaluatedBefore(Instant.EPOCH);
        reportLinks.listByAgent("nobody");
        truncateTables(DATA_SOURCE, "health_profiles", "health_alert_state", "health_report_links");
    }

    @Override
    public IHealthProfileStore profiles() {
        return profiles;
    }

    @Override
    public IHealthAlertStateStore alertStates() {
        return alertStates;
    }

    @Override
    public IHealthReportLinkStore reportLinks() {
        return reportLinks;
    }
}
