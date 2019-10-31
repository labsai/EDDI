package ai.labs.resources.rest.migration;

import ai.labs.resources.rest.migration.model.MigrationLog;

public interface IMigrationLogStore {
    MigrationLog readMigrationLog(String name);

    void createMigrationLog(MigrationLog migrationLog);
}
