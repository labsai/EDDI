/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;

import java.util.List;

public interface IMigrationLogStore {
    MigrationLog readMigrationLog(String name);

    void createMigrationLog(MigrationLog migrationLog);

    /**
     * The entries a migration keeps under {@code name} in the migration log — a
     * list it carries from one start to the next, such as the documents it has
     * already reported. Empty when there is no such record.
     *
     * <p>
     * A store that cannot keep entries answers empty and ignores
     * {@link #writeMigrationEntries}, so a migration using them behaves as on a
     * first start every time: it reports again what it reported before, which is
     * the safe direction. Only the MongoDB store keeps them; the migration that
     * uses them reads MongoDB collections.
     * </p>
     */
    default List<String> readMigrationEntries(String name) {
        return List.of();
    }

    /**
     * Replaces the entries kept under {@code name}; an empty list removes the
     * record. See {@link #readMigrationEntries}.
     */
    default void writeMigrationEntries(String name, List<String> entries) {
        // not kept — see readMigrationEntries
    }
}
