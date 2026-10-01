/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** {@link WorkspaceNotificationStoreContract} against a real PostgreSQL. */
@DisplayName("PostgresWorkspaceNotificationStore")
class PostgresWorkspaceNotificationStoreTest extends PostgresTestBase implements WorkspaceNotificationStoreContract {

    private PostgresWorkspaceNotificationStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSources = createDataSourceInstance();
        store = new PostgresWorkspaceNotificationStore(dataSources);
        store.countUnread("nobody");
        try (Connection connection = dataSources.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE workspace_notifications");
        }
    }

    @Override
    public IWorkspaceNotificationStore store() {
        return store;
    }
}
