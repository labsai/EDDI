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
import java.util.Locale;
import java.util.Set;

/** {@link EmailMessageLedgerContract} against PostgreSQL (Testcontainers). */
class PostgresEmailMessageLedgerIT extends PostgresTestBase implements EmailMessageLedgerContract {

    private static PostgresEmailMessageLedger store;
    private static DataSource dataSource;

    @BeforeAll
    static void createStore() {
        dataSource = createDataSource();
        store = new PostgresEmailMessageLedger(createDataSourceInstance());
        store.countPending(); // creates the schema
    }

    @Override
    public IEmailMessageLedger ledger() {
        return store;
    }

    @Override
    public void clear() throws Exception {
        truncateTables(dataSource, PostgresEmailMessageLedger.TABLE);
    }

    @Override
    public Set<String> storedFieldNames() throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '"
                        + PostgresEmailMessageLedger.TABLE + "'")) {
            while (columns.next()) {
                names.add(columns.getString(1).replace("_", "").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
