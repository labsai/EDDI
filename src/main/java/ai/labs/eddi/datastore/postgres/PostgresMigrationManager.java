/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.migration.IDocumentMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.LegacyDocumentMigrations;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * PostgreSQL implementation of {@link IMigrationManager}.
 * <p>
 * The startup <em>sweep</em> is a no-op: PostgreSQL starts with a clean schema
 * (tables created on first use), so there is no MongoDB-era corpus sitting in
 * collections waiting to be rewritten.
 * <p>
 * The three document transforms are <em>not</em> no-ops, and used to be. They
 * have a second caller with nothing to do with startup:
 * {@code RestImportService} runs them over every uploaded agent ZIP, and a 5.x
 * ZIP is exactly as legacy-shaped whichever backend receives it. Returning
 * {@code document -> null} here meant the same artefact imported differently
 * per {@code eddi.datastore.type} — a legacy output set threw instead of being
 * upgraded, and a legacy {@code targetServer} was dropped as an unknown
 * property, leaving every HTTP call in the imported agent with a null base URL,
 * with nothing in the response or the log naming the backend as the cause. Both
 * implementations now delegate to the same backend-independent
 * {@link LegacyDocumentMigrations}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresMigrationManager implements IMigrationManager {

    private static final Logger LOGGER = Logger.getLogger(PostgresMigrationManager.class);

    @Override
    public void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished) {
        LOGGER.info("PostgreSQL mode — no MongoDB collection sweep needed.");
        migrationFinished.onComplete();
    }

    @Override
    public IDocumentMigration migratePropertySetter() {
        return LegacyDocumentMigrations.propertySetter();
    }

    @Override
    public IDocumentMigration migrateApiCalls() {
        return LegacyDocumentMigrations.apiCalls();
    }

    @Override
    public IDocumentMigration migrateOutput() {
        return LegacyDocumentMigrations.output();
    }
}
