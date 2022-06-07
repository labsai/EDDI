package ai.labs.eddi.configs.migration;

public interface IMigrationManager {
    void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished);

    IDocumentMigration migratePropertySetter();

    IDocumentMigration migrateHttpCalls();

    IDocumentMigration migrateOutput();

    interface IMigrationFinished {
        void onComplete();
    }
}
