/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.health.HealthAdminStoresContract;
import ai.labs.eddi.engine.health.IHealthAlertStateStore;
import ai.labs.eddi.engine.health.IHealthProfileStore;
import ai.labs.eddi.engine.health.IHealthReportLinkStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthAlertStateStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthProfileStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthReportLinkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * {@link HealthAdminStoresContract} against MongoDB (Testcontainers).
 */
@DisplayName("Agent Health profile, alert-state and report-link stores (MongoDB)")
class MongoHealthAdminStoresIT extends MongoTestBase implements HealthAdminStoresContract {

    private IHealthProfileStore profiles;
    private IHealthAlertStateStore alertStates;
    private IHealthReportLinkStore reportLinks;

    @BeforeEach
    void setUp() {
        dropCollections("healthprofiles", "healthalertstate", "healthreportlinks");
        profiles = new MongoHealthProfileStore(getDatabase());
        alertStates = new MongoHealthAlertStateStore(getDatabase());
        reportLinks = new MongoHealthReportLinkStore(getDatabase());
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
