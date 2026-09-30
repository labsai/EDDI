/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** {@link UserDirectoryStoreContract} against a real PostgreSQL. */
@DisplayName("PostgresUserDirectoryStore")
class PostgresUserDirectoryStoreTest extends PostgresTestBase implements UserDirectoryStoreContract {

    private PostgresUserDirectoryStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSources = createDataSourceInstance();
        store = new PostgresUserDirectoryStore(dataSources);
        // Touch the store so the table exists, then empty it.
        store.find("nobody");
        try (Connection connection = dataSources.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE user_directory");
        }
    }

    @Override
    public IUserDirectoryStore store() {
        return store;
    }
}
