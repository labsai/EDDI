/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

public interface IMigrationManager {
    void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished);

    IDocumentMigration migratePropertySetter();

    IDocumentMigration migrateApiCalls();

    IDocumentMigration migrateOutput();

    /**
     * The 5.x to v6 transform of an LLM task document. Only for a document known to
     * come from 5.x, see {@link LegacyDocumentMigrations#llm()}. Not backend
     * specific, so both implementations share this default.
     */
    default IDocumentMigration migrateLlm() {
        return LegacyDocumentMigrations.llm();
    }

    interface IMigrationFinished {
        void onComplete();
    }
}
