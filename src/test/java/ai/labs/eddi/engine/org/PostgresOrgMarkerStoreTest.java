/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import jakarta.enterprise.inject.Instance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** {@link OrgMarkerStoreContractTest} against a real PostgreSQL. */
class PostgresOrgMarkerStoreTest extends OrgMarkerStoreContractTest {

    private static final Instance<DataSource> DATA_SOURCE = PostgresTestBase.createDataSourceInstance();

    @Override
    IOrgMarkerStore emptyStore() {
        execute("DROP TABLE IF EXISTS " + PostgresOrgMarkerStore.TABLE);
        return new PostgresOrgMarkerStore(DATA_SOURCE);
    }

    @Override
    boolean storageExists() {
        try (Connection connection = DATA_SOURCE.get().getConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT to_regclass('" + PostgresOrgMarkerStore.TABLE + "') IS NOT NULL")) {
            row.next();
            return row.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    void storeUnknownState(String state) {
        execute("UPDATE " + PostgresOrgMarkerStore.TABLE + " SET marker_state = '" + state + "' WHERE id = '" + PostgresOrgMarkerStore.RECORD_ID
                + "'");
    }

    private static void execute(String sql) {
        try (Connection connection = DATA_SOURCE.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
