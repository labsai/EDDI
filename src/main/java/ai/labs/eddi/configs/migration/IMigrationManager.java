/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

public interface IMigrationManager {
    void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished);

    IDocumentMigration migratePropertySetter();

    IDocumentMigration migrateApiCalls();

    IDocumentMigration migrateOutput();

    interface IMigrationFinished {
        void onComplete();
    }
}
