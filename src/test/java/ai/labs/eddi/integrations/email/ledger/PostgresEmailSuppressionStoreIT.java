/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import org.junit.jupiter.api.BeforeAll;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * {@link EmailSuppressionStoreContract} against PostgreSQL (Testcontainers).
 */
class PostgresEmailSuppressionStoreIT extends PostgresTestBase implements EmailSuppressionStoreContract {

    private static PostgresEmailSuppressionStore store;
    private static DataSource dataSource;

    @BeforeAll
    static void createStore() {
        dataSource = createDataSource();
        store = new PostgresEmailSuppressionStore(createDataSourceInstance());
        store.isSuppressed("int-0", "schema@example.com"); // creates the schema
    }

    @Override
    public IEmailSuppressionStore store() {
        return store;
    }

    @Override
    public void clear() throws Exception {
        truncateTables(dataSource, PostgresEmailSuppressionStore.TABLE);
    }

    @Override
    public Set<String> storedValues() throws Exception {
        Set<String> values = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT * FROM " + PostgresEmailSuppressionStore.TABLE)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                for (int i = 1; i <= columns; i++) {
                    values.add(String.valueOf(rows.getObject(i)));
                }
            }
        }
        return values;
    }
}
