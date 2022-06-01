package ai.labs.eddi.configs.migration;

import org.bson.Document;

import java.util.Map;

public interface IMigrationManager {
    void startMigration();

    Document migrateOutput(Map<String, Object> outputMap);
}
