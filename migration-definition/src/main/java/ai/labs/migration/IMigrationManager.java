package ai.labs.migration;

public interface IMigrationManager {
    void checkForMigration();

    class MigrationException extends Exception {
        public MigrationException(String message, Exception e) {
            super(message, e);
        }
    }
}
