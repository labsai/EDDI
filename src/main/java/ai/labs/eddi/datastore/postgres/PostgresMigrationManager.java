package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.migration.IDocumentMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * No-op PostgreSQL implementation of {@link IMigrationManager}.
 * <p>
 * PostgreSQL starts with a clean schema (tables created on first use),
 * so document-level migrations from the MongoDB era are not applicable.
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresMigrationManager implements IMigrationManager {

    private static final Logger LOGGER = Logger.getLogger(PostgresMigrationManager.class);

    @Override
    public void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished) {
        LOGGER.info("PostgreSQL mode — no MongoDB migrations needed.");
        migrationFinished.onComplete();
    }

    @Override
    public IDocumentMigration migratePropertySetter() {
        return document -> null;
    }

    @Override
    public IDocumentMigration migrateApiCalls() {
        return document -> null;
    }

    @Override
    public IDocumentMigration migrateOutput() {
        return document -> null;
    }
}
