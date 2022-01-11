package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;

public interface IMigrationLogStore {
    MigrationLog readMigrationLog(String name);

    void createMigrationLog(MigrationLog migrationLog);
}
